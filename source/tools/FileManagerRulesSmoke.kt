import com.abfilepro.app.core.FileManagerRules

private fun expect(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    expect(FileManagerRules.safeName(" report:2026?.pdf ") == "report_2026_.pdf", "safeName")
    expect(FileManagerRules.safeName("..") == "", "dot path")
    expect(FileManagerRules.uniqueName("report.pdf", setOf("report.pdf", "report (2).pdf")) == "report (3).pdf", "uniqueName")
    expect(FileManagerRules.isSafeZipPath("folder/document.txt"), "safe zip")
    expect(!FileManagerRules.isSafeZipPath("../secret.txt"), "traversal")
    val entries = listOf(
        FileManagerRules.EntryMeta("z.pdf", false, 300, 10, "application/pdf"),
        FileManagerRules.EntryMeta("Folder B", true, 0, 5, "vnd.android.document/directory"),
        FileManagerRules.EntryMeta("a.txt", false, 100, 30, "text/plain"),
        FileManagerRules.EntryMeta("Folder A", true, 0, 20, "vnd.android.document/directory")
    )
    expect(
        FileManagerRules.sortEntries(entries, FileManagerRules.SortKey.NAME, true).map { it.name } ==
            listOf("Folder A", "Folder B", "a.txt", "z.pdf"),
        "sort name"
    )
    println("FileManagerRulesSmoke: PASS")
}
