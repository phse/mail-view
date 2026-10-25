package app.heckit.ide.mailview

import com.intellij.DynamicBundle
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import com.intellij.testFramework.LightVirtualFile
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JLabel
import javax.swing.JViewport
import javax.swing.table.TableCellRenderer
import java.awt.Component
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

fun isMbox(file: VirtualFile) = file.extension?.lowercase() in setOf("mbox", "mbx")

class MboxEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = !file.isDirectory && isMbox(file)
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = MboxEditor(project, file)
    override fun getEditorTypeId() = "mail-view-mbox"
    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

/**
 * Reiter „Mails“ einer mbox: Liste mit Anhängen, Absender, Betreff, Datum und Größe; Doppelklick oder Enter öffnet
 * die Mail als Tab. Spaltenreihenfolge und Sortierung gelten für alle mbox-Dateien; die Breiten passen sich jeder Datei an,
 * bis man sie von Hand ändert - dann gelten diese für die Datei.
 */
class MboxEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private var entries: List<MboxEntry> = emptyList()
    /** Nicht lokale Dateien (z. B. in einem Archiv) liegen im Speicher, lokale werden gezielt gelesen. */
    private var bytes: ByteArray? = null
    private val cancelled = AtomicBoolean()
    private var restoring = false
    /** Breiten von Hand gesetzt (für diese Datei gespeichert): dann nicht mehr automatisch anpassen. */
    private var manualWidths = false
    private var userResizing = false
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, DynamicBundle.getLocale())

    private val model = object : AbstractTableModel() {
        override fun getRowCount() = entries.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int): String = if (column == ATTACHMENTS) "" else MailViewBundle.message(COLUMNS[column])
        override fun getColumnClass(column: Int): Class<*> = when (column) {
            DATE -> Date::class.java
            SIZE -> java.lang.Long::class.java
            ATTACHMENTS -> Integer::class.java
            else -> String::class.java
        }
        override fun getValueAt(row: Int, column: Int): Any? = entries[row].let {
            when (column) {
                ATTACHMENTS -> it.attachments
                FROM -> it.from ?: ""
                SUBJECT -> it.subject ?: MailViewBundle.message("header.noSubject")
                DATE -> it.date
                else -> it.size
            }
        }
    }
    private val sorter = TableRowSorter(model).apply {
        setComparator(DATE, nullsFirst(compareBy<Date> { it }))
        setComparator(SUBJECT, String.CASE_INSENSITIVE_ORDER)
        setComparator(FROM, String.CASE_INSENSITIVE_ORDER)
    }
    private val table = JBTable(model).apply {
        rowSorter = sorter
        setShowGrid(false)
        rowHeight = JBUI.scale(26)
        emptyText.text = MailViewBundle.message("mbox.loading")
        tableHeader.reorderingAllowed = true
        setDefaultRenderer(Any::class.java, PaddedRenderer { value, _ -> value?.toString() ?: "" })
        setDefaultRenderer(Date::class.java, PaddedRenderer { value, _ -> format(DATE, value) })
        setDefaultRenderer(java.lang.Long::class.java, PaddedRenderer(SwingConstants.RIGHT) { value, _ -> format(SIZE, value) })
        setDefaultRenderer(Integer::class.java, PaddedRenderer(SwingConstants.CENTER) { value, label ->
            val count = value as? Int ?: 0
            label.toolTipText = if (count > 0) MailViewBundle.message("attachments.label", count).removeSuffix(":") else null
            format(ATTACHMENTS, value)
        })
        // Kopf der Anhänge-Spalte: nur die Büroklammer.
        val headerDefault = tableHeader.defaultRenderer
        tableHeader.defaultRenderer = TableCellRenderer { t, value, selected, focus, row, column ->
            headerDefault.getTableCellRendererComponent(t, value, selected, focus, row, column).also { c ->
                val label = c as? JLabel ?: return@also
                if (t.convertColumnIndexToModel(column) == ATTACHMENTS) {
                    label.icon = MailIcons.ATTACHMENT
                    label.text = ""
                    label.toolTipText = MailViewBundle.message("mbox.attachments")
                } else if (label.icon === MailIcons.ATTACHMENT) {
                    label.icon = null
                    label.toolTipText = null
                }
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) openSelected() }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) { openSelected(); e.consume() } }
        })
    }
    private val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val search = SearchTextField(false).apply {
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = filter(text)
        })
    }
    private val listPanel = BorderLayoutPanel()
        .addToTop(BorderLayoutPanel().addToCenter(search).addToRight(status.apply { border = JBUI.Borders.emptyLeft(10) })
            .apply { border = JBUI.Borders.empty(6, 8) })
        .addToCenter(JBScrollPane(table).apply { border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()) })
    /** Oben die Liste, unten die angeklickte Mail (erst nach dem ersten Klick). */
    private val splitter = OnePixelSplitter(true, "mailview.mbox.split", 0.4f).apply { firstComponent = listPanel }
    private val root: JComponent = splitter
    private var preview: MailPreviewEditor? = null
    private var previewEntry: MboxEntry? = null
    private var previewRequest = 0

    init {
        restoreLayout()
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showPreview() }
        table.tableHeader.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) { if (table.tableHeader.resizingColumn != null) userResizing = true }
        })
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (!userResizing) return
                userResizing = false
                manualWidths = true
                saveWidths()
            }
        })
        listPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) { if (!manualWidths && entries.isNotEmpty()) fitColumns() }
        })
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                if (file.isInLocalFileSystem) MboxIndex.scan(file.toNioPath()) { cancelled.get() }
                else file.contentsToByteArray().let { data -> bytes = data; MboxIndex.scan(data.inputStream()) }
            }
            ApplicationManager.getApplication().invokeLater({ show(result) }, { cancelled.get() || !file.isValid })
        }
    }

    /** Zellen mit etwas Luft links und rechts. */
    private class PaddedRenderer(private val align: Int = SwingConstants.LEFT, private val text: (Any?, JBLabel) -> String) :
        DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
            toolTipText = null
            val label = JBLabel()
            val shown = text(value, label)
            super.getTableCellRendererComponent(t, shown, selected, false, row, column)
            toolTipText = label.toolTipText ?: shown.takeIf { it.isNotEmpty() && column != ATTACHMENTS }
            horizontalAlignment = align
            border = JBUI.Borders.empty(0, 8)
            return this
        }
    }

    /** Reihenfolge (als Modellspalten), Breiten und Sortierung wie zuletzt eingestellt; sonst neueste Mails oben. */
    private fun restoreLayout() {
        restoring = true
        val props = PropertiesComponent.getInstance()
        val columns = table.columnModel
        props.getValue(KEY_ORDER)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.sorted() == COLUMNS.indices.toList() }?.let { order ->
            order.forEachIndexed { target, model ->
                val current = (0 until columns.columnCount).first { columns.getColumn(it).modelIndex == model }
                if (current != target) columns.moveColumn(current, target)
            }
        }
        val sortColumn = props.getInt(KEY_SORT_COLUMN, DATE).coerceIn(COLUMNS.indices)
        val order = runCatching { SortOrder.valueOf(props.getValue(KEY_SORT_ORDER) ?: "") }.getOrDefault(SortOrder.DESCENDING)
        sorter.sortKeys = listOf(RowSorter.SortKey(sortColumn, order))
        restoring = false

        columns.addColumnModelListener(object : TableColumnModelListener {
            override fun columnMoved(e: TableColumnModelEvent) { if (e.fromIndex != e.toIndex) saveLayout() }
            override fun columnMarginChanged(e: ChangeEvent) = Unit
            override fun columnAdded(e: TableColumnModelEvent) = Unit
            override fun columnRemoved(e: TableColumnModelEvent) = Unit
            override fun columnSelectionChanged(e: ListSelectionEvent) = Unit
        })
        sorter.addRowSorterListener {
            sorter.sortKeys.firstOrNull()?.let {
                props.setValue(KEY_SORT_COLUMN, it.column, DATE)
                props.setValue(KEY_SORT_ORDER, it.sortOrder.name)
            }
        }
    }

    private fun saveLayout() {
        if (restoring) return
        val columns = table.columnModel
        val props = PropertiesComponent.getInstance()
        props.setValue(KEY_ORDER, (0 until columns.columnCount).joinToString(",") { columns.getColumn(it).modelIndex.toString() })
    }

    private val widthsKey get() = "mailview.mbox.widths." + file.url.hashCode().toUInt().toString(16)

    private fun saveWidths() {
        val columns = table.columnModel
        val widths = IntArray(COLUMNS.size)
        for (i in 0 until columns.columnCount) columns.getColumn(i).let { widths[it.modelIndex] = it.width }
        PropertiesComponent.getInstance().setValue(widthsKey, widths.joinToString(","))
    }

    /** Von Hand gesetzte Breiten dieser Datei; sonst passend zum Inhalt. */
    private fun applyWidths() {
        val saved = PropertiesComponent.getInstance().getValue(widthsKey)?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == COLUMNS.size }
        if (saved == null) return fitColumns()
        manualWidths = true
        setWidths(saved)
    }

    /**
     * Anhänge, Datum und Größe so breit, dass nichts abgeschnitten wird; Von so breit wie nötig, höchstens so breit wie
     * Betreff; Betreff bekommt den Rest.
     */
    private fun fitColumns() {
        val available = (table.parent as? JViewport)?.width?.takeIf { it > 0 } ?: return
        val metrics = table.getFontMetrics(table.font)
        val padding = JBUI.scale(16) + 2
        fun header(column: Int): Int {
            val index = table.convertColumnIndexToView(column)
            return table.tableHeader.defaultRenderer.getTableCellRendererComponent(table, model.getColumnName(column), false, false, -1, index)
                .preferredSize.width
        }
        fun content(column: Int): Int = maxOf(header(column), (entries.indices.maxOfOrNull { metrics.stringWidth(format(column, model.getValueAt(it, column))) } ?: 0) + padding)
        val widths = IntArray(COLUMNS.size)
        widths[ATTACHMENTS] = content(ATTACHMENTS)
        widths[DATE] = content(DATE)
        widths[SIZE] = content(SIZE)
        val rest = available - widths[ATTACHMENTS] - widths[DATE] - widths[SIZE]
        widths[FROM] = minOf(content(FROM), rest / 2)
        widths[SUBJECT] = maxOf(JBUI.scale(80), rest - widths[FROM])
        setWidths(widths.toList())
    }

    private fun setWidths(widths: List<Int>) {
        val columns = table.columnModel
        for (i in 0 until columns.columnCount) columns.getColumn(i).let {
            it.preferredWidth = widths[it.modelIndex]
            it.width = widths[it.modelIndex]
        }
    }

    /** Zellentext wie in der Tabelle, auch zum Ausmessen der Spalten. */
    private fun format(column: Int, value: Any?): String = when (column) {
        DATE -> (value as? Date)?.let(dateFormat::format) ?: ""
        SIZE -> (value as? Long)?.let(StringUtil::formatFileSize) ?: ""
        ATTACHMENTS -> (value as? Int)?.takeIf { it > 0 }?.toString() ?: ""
        else -> value?.toString() ?: ""
    }

    private fun show(result: Result<List<MboxEntry>>) {
        result.onSuccess {
            entries = it
            model.fireTableDataChanged()
            applyWidths()
            table.emptyText.text = MailViewBundle.message("mbox.empty")
            updateStatus()
        }.onFailure {
            table.emptyText.text = MailViewBundle.message("preview.error", it.message ?: it.javaClass.simpleName)
        }
    }

    private fun filter(text: String) {
        val words = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        sorter.rowFilter = if (words.isEmpty()) null else object : RowFilter<AbstractTableModel, Int>() {
            override fun include(entry: Entry<out AbstractTableModel, out Int>): Boolean {
                val e = entries[entry.identifier]
                val haystack = "${e.from ?: ""} ${e.subject ?: ""}".lowercase()
                return words.all { it in haystack }
            }
        }
        updateStatus()
    }

    private fun updateStatus() {
        status.text = if (table.rowCount == entries.size) MailViewBundle.message("mbox.count", entries.size)
        else MailViewBundle.message("mbox.count.filtered", table.rowCount, entries.size)
    }

    private fun selectedEntry(): MboxEntry? = table.selectedRow.takeIf { it >= 0 }?.let { entries[table.convertRowIndexToModel(it)] }

    private fun read(entry: MboxEntry): ByteArray = bytes?.let { MboxIndex.read(it, entry) } ?: MboxIndex.read(file.toNioPath(), entry)

    /**
     * Angeklickte Mail unter der Liste zeigen: lesen und parsen im Hintergrund, angezeigt wird nur das Ergebnis der
     * zuletzt gewählten Zeile (schnelles Blättern überholt ältere Anfragen).
     */
    private fun showPreview() {
        val entry = selectedEntry() ?: return
        if (entry === previewEntry) return
        previewEntry = entry
        val request = ++previewRequest
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { MailParser.parse(read(entry)) }
            ApplicationManager.getApplication().invokeLater({ if (request == previewRequest) previewEditor().showMail(result) }, { cancelled.get() })
        }
    }

    private fun previewEditor(): MailPreviewEditor = preview ?: MailPreviewEditor(project, LightVirtualFile("mail.eml"), controls).also {
        Disposer.register(this, it)
        preview = it
        splitter.secondComponent = it.component
    }

    private val controls = object : MailListControls {
        override fun canMove(delta: Int) = (table.selectedRow + delta) in 0 until table.rowCount && table.selectedRow >= 0
        override fun move(delta: Int) {
            if (!canMove(delta)) return
            val row = table.selectedRow + delta
            table.setRowSelectionInterval(row, row)
            table.scrollRectToVisible(table.getCellRect(row, 0, true))
        }
        override fun position() = if (table.selectedRow < 0) "" else "${table.selectedRow + 1} / ${table.rowCount}"
        override fun openInTab() = openSelected()
    }

    /** Doppelklick, Enter oder „Im Tab öffnen“: die Mail mit allen Reitern (Inhalt, Header, Text) als eigener Tab. */
    private fun openSelected() {
        val entry = selectedEntry() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val mail = runCatching { MailTempFiles.create(entry, read(entry)) }
            ApplicationManager.getApplication().invokeLater({
                mail.onSuccess { it?.let { file -> OpenFileDescriptor(project, file).navigate(true) } }
                    .onFailure { Messages.showErrorDialog(project, it.message ?: it.toString(), MailViewBundle.message("mbox.open.error")) }
            }, { cancelled.get() })
        }
    }

    /** Für die Marketplace-Screenshots. */
    internal fun selectRow(row: Int) {
        if (row < table.rowCount) table.setRowSelectionInterval(row, row)
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = table
    override fun getName(): String = MailViewBundle.message("editor.mbox")
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun getFile(): VirtualFile = file
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = cancelled.set(true)

    private companion object {
        const val ATTACHMENTS = 0
        const val FROM = 1
        const val SUBJECT = 2
        const val DATE = 3
        const val SIZE = 4
        val COLUMNS = listOf("mbox.attachments", "header.from", "mbox.subject", "header.date", "mbox.size")
        const val KEY_ORDER = "mailview.mbox.columns"
        const val KEY_SORT_COLUMN = "mailview.mbox.sortColumn"
        const val KEY_SORT_ORDER = "mailview.mbox.sortOrder"
    }
}
