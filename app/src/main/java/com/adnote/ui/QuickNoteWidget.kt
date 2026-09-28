package com.adnote.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.adnote.R

/** 桌面小部件：点一下新建速记并直接进入手写。 */
class QuickNoteWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_QUICK_NOTE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_note)
            views.setOnClickPendingIntent(R.id.widgetRoot, pending)
            manager.updateAppWidget(id, views)
        }
    }
}
