package com.mtplugin.aieditor;

import androidx.annotation.NonNull;

import android.graphics.drawable.Drawable;

import com.mtplugin.aieditor.ui.AboutDialog;
import com.mtplugin.aieditor.ui.AiEditDialog;

import bin.mt.plugin.api.drawable.MaterialIcons;
import bin.mt.plugin.api.editor.BaseTextEditorToolMenu;
import bin.mt.plugin.api.editor.TextEditor;
import bin.mt.plugin.api.ui.PluginUI;

/**
 * Text editor "Edit" toolbar menu item: AI Edit.
 *
 * User entry: "AI Edit" item in the "Edit" menu of the MT Manager text editor top toolbar.
 * The button on the right of the menu item (onPluginButtonClick) pops up the "About / Community" dialog.
 */
public class AiEditToolMenu extends BaseTextEditorToolMenu {

    @NonNull
    @Override
    public String name() {
        // Localized text, see assets/strings*.mtl
        return "{menu_ai_edit}";
    }

    @NonNull
    @Override
    public Drawable icon() {
        try {
            return MaterialIcons.get("auto_awesome");
        } catch (Throwable t) {
            // Icon name fallback, ensure no crash
            return MaterialIcons.get("edit");
        }
    }

    @Override
    public boolean checkVisible(@NonNull TextEditor editor) {
        // Not shown in read-only mode
        return editor != null && !editor.isReadOnly();
    }

    @Override
    public void onMenuClick(@NonNull PluginUI pluginUI, @NonNull TextEditor editor) {
        AiEditDialog.show(pluginUI, editor);
    }

    @Override
    public void onPluginButtonClick(@NonNull PluginUI pluginUI) {
        // ⓘ Button: About / Community dialog (website, QQ channel, group numbers)
        AboutDialog.show(pluginUI);
    }
}