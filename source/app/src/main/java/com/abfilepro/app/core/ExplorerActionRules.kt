package com.abfilepro.app.core

enum class ExplorerAction {
    OPEN,
    COPY,
    CUT,
    PASTE,
    RENAME,
    DUPLICATE,
    OPEN_WITH,
    FAVORITE,
    SHARE,
    ZIP,
    IMAGES_TO_PDF,
    EXTRACT_HERE,
    EXTRACT_FOLDER,
    PROPERTIES,
    DELETE
}

object ExplorerActionRules {
    fun actionsFor(
        isDirectory: Boolean,
        extension: String,
        isImage: Boolean,
        clipboardAvailable: Boolean
    ): List<ExplorerAction> = buildList {
        add(ExplorerAction.OPEN)
        add(ExplorerAction.COPY)
        add(ExplorerAction.CUT)
        if (clipboardAvailable) add(ExplorerAction.PASTE)
        add(ExplorerAction.RENAME)
        add(ExplorerAction.DUPLICATE)
        if (!isDirectory) add(ExplorerAction.OPEN_WITH)
        add(ExplorerAction.FAVORITE)
        if (!isDirectory) add(ExplorerAction.SHARE)
        add(ExplorerAction.ZIP)
        if (isImage) add(ExplorerAction.IMAGES_TO_PDF)
        if (!isDirectory && extension.equals("zip", ignoreCase = true)) {
            add(ExplorerAction.EXTRACT_HERE)
            add(ExplorerAction.EXTRACT_FOLDER)
        }
        add(ExplorerAction.PROPERTIES)
        add(ExplorerAction.DELETE)
    }
}
