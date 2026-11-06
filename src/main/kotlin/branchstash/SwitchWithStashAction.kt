package branchstash

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtil
import git4idea.branch.GitBranchUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import git4idea.validators.GitRefNameValidator

private const val MARKER = "autostash:"

/** Ref of the newest plugin stash for [branch], from `git stash list --format="%gd %gs"` lines. */
fun findAutostash(lines: List<String>, branch: String): String? =
    lines.firstOrNull { it.endsWith(": $MARKER$branch") }?.substringBefore(' ')

abstract class StashAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
    }

    protected fun start(project: Project, repo: GitRepository, target: String, create: Boolean) {
        // Unsaved editor changes would otherwise miss the stash.
        FileDocumentManager.getInstance().saveAllDocuments()
        SwitchTask(project, repo, target, create).queue()
    }
}

class NewBranchWithStashAction : StashAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = GitBranchUtil.guessRepositoryForOperation(project, e.dataContext) ?: return
        val name = Messages.showInputDialog(
            project, "New branch name (current changes are stashed, the new branch starts clean):",
            "New Branch with Stash", null, "", GitRefNameValidator.getInstance(),
        ) ?: return
        start(project, repo, name, create = true)
    }
}

class SwitchWithStashAction : StashAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = GitBranchUtil.guessRepositoryForOperation(project, e.dataContext) ?: return
        val branches = repo.branches.localBranches.map { it.name }.filter { it != repo.currentBranchName }.sorted()
        if (branches.isEmpty()) return notify(project, listOf("No other local branches"), NotificationType.INFORMATION)

        JBPopupFactory.getInstance().createPopupChooserBuilder(branches)
            .setTitle("Switch Branch with Stash")
            .setNamerForFiltering { it }
            .setItemChosenCallback { target -> start(project, repo, target, create = false) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}

private class SwitchTask(
    private val proj: Project,
    private val repo: GitRepository,
    private val target: String,
    private val create: Boolean,
) : Task.Backgroundable(proj, "Switching to $target") {

    override fun run(indicator: ProgressIndicator) {
        try {
            switch()
        } finally {
            repo.update()
            VfsUtil.markDirtyAndRefresh(false, true, true, repo.root)
            VcsDirtyScopeManager.getInstance(proj).markEverythingDirty()
        }
    }

    private fun switch() {
        val from = repo.currentBranchName
            ?: return notify(proj, listOf("Detached HEAD: check out a branch first"), NotificationType.WARNING)

        val status = git(GitCommand.STATUS, "--porcelain")
        if (!status.success()) return fail("git status failed", status)
        val stashed = status.output.isNotEmpty()
        if (stashed) {
            val push = git(GitCommand.STASH, "push", "--include-untracked", "-m", "$MARKER$from")
            if (!push.success()) return fail("Stash failed, nothing changed", push)
        }

        val checkout = if (create) git(GitCommand.CHECKOUT, "-b", target) else git(GitCommand.CHECKOUT, target)
        if (!checkout.success()) {
            if (!stashed) return fail("Checkout of $target failed", checkout)
            // Put the changes back onto the branch we never left.
            val back = git(GitCommand.STASH, "pop")
            return fail(
                if (back.success()) "Checkout of $target failed, changes restored"
                else "Checkout of $target failed; changes are kept in stash ($MARKER$from)",
                checkout,
            )
        }

        val lines = mutableListOf(if (create) "Created and switched to $target" else "Switched to $target")
        if (stashed) lines += "Changes of $from stashed"
        var type = NotificationType.INFORMATION
        // A new branch starts clean, even if a stale stash of a deleted branch with the same name exists.
        val list = if (create) null else git(GitCommand.STASH, "list", "--format=%gd %gs")
        val ref = if (list?.success() == true) findAutostash(list.output, target) else null
        if (ref != null) {
            val pop = git(GitCommand.STASH, "pop", ref)
            if (pop.success()) {
                lines += "Stash of $target restored"
            } else {
                type = NotificationType.WARNING
                lines += "Restoring $ref failed, the stash is kept. Resolve conflicts, then drop it."
                lines += pop.errorOutputAsJoinedString
            }
        }
        notify(proj, lines, type)
    }

    private fun fail(message: String, result: GitCommandResult) =
        notify(proj, listOf(message, result.errorOutputAsJoinedString), NotificationType.ERROR)

    private fun git(command: GitCommand, vararg params: String): GitCommandResult =
        Git.getInstance().runCommand(GitLineHandler(proj, repo.root, command).apply { addParameters(*params) })
}

private fun notify(project: Project, lines: List<String>, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("Branch Stash")
        .createNotification(lines.filter { it.isNotBlank() }.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }, type)
        .notify(project)
}
