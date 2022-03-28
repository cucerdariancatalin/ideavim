/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2022 The IdeaVim authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.maddyhome.idea.vim.group.visual

import com.intellij.find.FindManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimMotionGroupBase
import com.maddyhome.idea.vim.api.VimVisualMotionGroupBase
import com.maddyhome.idea.vim.command.CommandState
import com.maddyhome.idea.vim.group.MotionGroup
import com.maddyhome.idea.vim.helper.EditorHelper
import com.maddyhome.idea.vim.helper.commandState
import com.maddyhome.idea.vim.helper.exitVisualMode
import com.maddyhome.idea.vim.helper.inVisualMode
import com.maddyhome.idea.vim.helper.pushSelectMode
import com.maddyhome.idea.vim.helper.pushVisualMode
import com.maddyhome.idea.vim.helper.subMode
import com.maddyhome.idea.vim.helper.vimForEachCaret
import com.maddyhome.idea.vim.helper.vimLastColumn
import com.maddyhome.idea.vim.helper.vimLastVisualOperatorRange
import com.maddyhome.idea.vim.helper.vimSelectionStart
import com.maddyhome.idea.vim.newapi.ij
import com.maddyhome.idea.vim.newapi.vim
import com.maddyhome.idea.vim.options.OptionConstants
import com.maddyhome.idea.vim.options.OptionScope
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString

/**
 * @author Alex Plate
 */
class VisualMotionGroup : VimVisualMotionGroupBase() {

  // =============================== ENTER VISUAL and SELECT MODE ==============================================

  /**
   * This function toggles visual mode.
   *
   * If visual mode is disabled, enable it
   * If visual mode is enabled, but [subMode] differs, update visual according to new [subMode]
   * If visual mode is enabled with the same [subMode], disable it
   */
  override fun toggleVisual(editor: VimEditor, count: Int, rawCount: Int, subMode: CommandState.SubMode): Boolean {
    if (!editor.inVisualMode) {
      // Enable visual subMode
      if (rawCount > 0) {
        val primarySubMode = editor.ij.caretModel.primaryCaret.vimLastVisualOperatorRange?.type?.toSubMode()
          ?: subMode
        editor.commandState.pushVisualMode(primarySubMode)

        editor.ij.vimForEachCaret {
          val range = it.vimLastVisualOperatorRange ?: VisualChange.default(subMode)
          val end = VisualOperation.calculateRange(editor.ij, range, count, it)
          val lastColumn =
            if (range.columns == VimMotionGroupBase.LAST_COLUMN) VimMotionGroupBase.LAST_COLUMN else editor.offsetToLogicalPosition(
              end
            ).column
          it.vimLastColumn = lastColumn
          it.vimSetSelection(it.offset, end, true)
        }
      } else {
        editor.commandState.pushVisualMode(subMode)
        editor.ij.vimForEachCaret { it.vimSetSelection(it.offset) }
      }
      return true
    }

    if (subMode == editor.subMode) {
      // Disable visual subMode
      editor.ij.exitVisualMode()
      return true
    }

    // Update visual subMode with new sub subMode
    editor.subMode = subMode
    for (caret in editor.carets()) {
      caret.ij.vimUpdateEditorSelection()
    }

    return true
  }

  @Deprecated("Use enterVisualMode or toggleVisual methods")
  fun setVisualMode(editor: Editor) {
    val autodetectedMode = autodetectVisualSubmode(editor)

    if (editor.inVisualMode) {
      editor.vim.commandState.popModes()
    }
    editor.vim.commandState.pushModes(CommandState.Mode.VISUAL, autodetectedMode)
    if (autodetectedMode == CommandState.SubMode.VISUAL_BLOCK) {
      val (start, end) = blockModeStartAndEnd(editor)
      editor.caretModel.removeSecondaryCarets()
      editor.caretModel.primaryCaret.vimSetSelection(start, (end - selectionAdj).coerceAtLeast(0), true)
    } else {
      editor.caretModel.allCarets.forEach {
        if (!it.hasSelection()) {
          it.vimSetSelection(it.offset)
          MotionGroup.moveCaret(editor, it, it.offset)
          return@forEach
        }

        val selectionStart = it.selectionStart
        val selectionEnd = it.selectionEnd
        if (selectionStart == it.offset) {
          it.vimSetSelection((selectionEnd - selectionAdj).coerceAtLeast(0), selectionStart, true)
        } else {
          it.vimSetSelection(selectionStart, (selectionEnd - selectionAdj).coerceAtLeast(0), true)
        }
      }
    }
    KeyHandler.getInstance().reset(editor.vim)
  }

  /**
   * Enters visual mode based on current editor state.
   * If [subMode] is null, subMode will be detected automatically
   *
   * it:
   * - Updates command state
   * - Updates [vimSelectionStart] property
   * - Updates caret colors
   * - Updates care shape
   *
   * - DOES NOT change selection
   * - DOES NOT move caret
   * - DOES NOT check if carets actually have any selection
   */
  fun enterVisualMode(editor: Editor, subMode: CommandState.SubMode? = null): Boolean {
    val autodetectedSubMode = subMode ?: autodetectVisualSubmode(editor)
    editor.vim.commandState.pushModes(CommandState.Mode.VISUAL, autodetectedSubMode)
    if (autodetectedSubMode == CommandState.SubMode.VISUAL_BLOCK) {
      editor.caretModel.primaryCaret.run { vimSelectionStart = vimLeadSelectionOffset }
    } else {
      editor.caretModel.allCarets.forEach { it.vimSelectionStart = it.vimLeadSelectionOffset }
    }
    return true
  }

  override fun enterSelectMode(editor: VimEditor, subMode: CommandState.SubMode): Boolean {
    editor.commandState.pushSelectMode(subMode)
    editor.forEachCaret { it.vimSelectionStart = it.vimLeadSelectionOffset }
    return true
  }

  fun autodetectVisualSubmode(editor: Editor): CommandState.SubMode {
    // IJ specific. See https://youtrack.jetbrains.com/issue/VIM-1924.
    val project = editor.project
    if (project != null && FindManager.getInstance(project).selectNextOccurrenceWasPerformed()) {
      return CommandState.SubMode.VISUAL_CHARACTER
    }

    if (editor.caretModel.caretCount > 1 && seemsLikeBlockMode(editor)) {
      return CommandState.SubMode.VISUAL_BLOCK
    }
    val all = editor.caretModel.allCarets.all { caret ->
      // Detect if visual mode is character wise or line wise
      val selectionStart = caret.selectionStart
      val selectionEnd = caret.selectionEnd
      val logicalStartLine = editor.offsetToLogicalPosition(selectionStart).line
      val logicalEnd = editor.offsetToLogicalPosition(selectionEnd)
      val logicalEndLine = if (logicalEnd.column == 0) (logicalEnd.line - 1).coerceAtLeast(0) else logicalEnd.line
      val lineStartOfSelectionStart = EditorHelper.getLineStartOffset(editor, logicalStartLine)
      val lineEndOfSelectionEnd = EditorHelper.getLineEndOffset(editor, logicalEndLine, true)
      lineStartOfSelectionStart == selectionStart && (lineEndOfSelectionEnd + 1 == selectionEnd || lineEndOfSelectionEnd == selectionEnd)
    }
    if (all) return CommandState.SubMode.VISUAL_LINE
    return CommandState.SubMode.VISUAL_CHARACTER
  }

  private fun seemsLikeBlockMode(editor: Editor): Boolean {
    val selections = editor.caretModel.allCarets.map {
      val adj = if (editor.offsetToLogicalPosition(it.selectionEnd).column == 0) 1 else 0
      it.selectionStart to (it.selectionEnd - adj).coerceAtLeast(0)
    }.sortedBy { it.first }
    val selectionStartColumn = editor.offsetToLogicalPosition(selections.first().first).column
    val selectionStartLine = editor.offsetToLogicalPosition(selections.first().first).line

    val maxColumn = selections.map { editor.offsetToLogicalPosition(it.second).column }.maxOrNull() ?: return false
    selections.forEachIndexed { i, it ->
      if (editor.offsetToLogicalPosition(it.first).line != editor.offsetToLogicalPosition(it.second).line) {
        return false
      }
      if (editor.offsetToLogicalPosition(it.first).column != selectionStartColumn) {
        return false
      }
      val lineEnd = editor.offsetToLogicalPosition(EditorHelper.getLineEndForOffset(editor, it.second)).column
      if (editor.offsetToLogicalPosition(it.second).column != maxColumn.coerceAtMost(lineEnd)) {
        return false
      }
      if (editor.offsetToLogicalPosition(it.first).line != selectionStartLine + i) {
        return false
      }
    }
    return true
  }

  private fun blockModeStartAndEnd(editor: Editor): Pair<Int, Int> {
    val selections = editor.caretModel.allCarets.map { it.selectionStart to it.selectionEnd }.sortedBy { it.first }
    val maxColumn =
      selections.map { editor.offsetToLogicalPosition(it.second).column }.maxOrNull() ?: error("No carets")
    val lastLine = editor.offsetToLogicalPosition(selections.last().first).line
    return selections.first().first to editor.logicalPositionToOffset(LogicalPosition(lastLine, maxColumn))
  }

  val exclusiveSelection: Boolean
    get() = (VimPlugin.getOptionService().getOptionValue(OptionScope.GLOBAL, OptionConstants.selectionName) as VimString).value == "exclusive"
  val selectionAdj: Int
    get() = if (exclusiveSelection) 0 else 1
}
