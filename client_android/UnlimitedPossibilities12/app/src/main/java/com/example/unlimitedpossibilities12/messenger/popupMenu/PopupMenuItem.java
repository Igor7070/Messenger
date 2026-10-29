package com.example.unlimitedpossibilities12.messenger.popupMenu;

import android.graphics.drawable.Drawable;

public class PopupMenuItem {

    public final String title;
    public final Drawable icon;
    public final int textColor;

    public PopupMenuItem(String title, Drawable icon, int textColor) {
        this.title = title;
        this.icon = icon;
        this.textColor = textColor;
    }
}
