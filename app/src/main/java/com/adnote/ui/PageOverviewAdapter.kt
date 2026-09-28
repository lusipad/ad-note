package com.adnote.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adnote.R
import com.adnote.model.Page
import com.adnote.pdf.PdfPageRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 页面概览网格。缩略图在后台线程绘制并缓存（按页面 id + 笔画列表版本做键，
 * 笔迹变化后自动重绘）。
 */
class PageOverviewAdapter(
    private val scope: CoroutineScope,
    private val pdfRenderer: PdfPageRenderer?,
    private val thumbWidthPx: Int,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int, View) -> Unit,
) : RecyclerView.Adapter<PageOverviewAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val frame: FrameLayout = view.findViewById(R.id.frameThumb)
        val image: ImageView = view.findViewById(R.id.ivThumb)
        val number: TextView = view.findViewById(R.id.tvPageNumber)
    }

    private var pages: List<Page> = emptyList()
    private var currentIndex: Int = 0
    private val cache = LruCache<String, Bitmap>(48)

    fun submit(pages: List<Page>, currentIndex: Int) {
        this.pages = pages
        this.currentIndex = currentIndex
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_page_thumb, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val page = pages[position]
        holder.number.text = "${position + 1}"
        holder.frame.setBackgroundResource(
            if (position == currentIndex) R.drawable.bg_page_thumb_current else R.drawable.bg_page_thumb
        )
        val thumbH = (thumbWidthPx.toLong() * page.height / page.width.coerceAtLeast(1)).toInt()
        holder.image.layoutParams = holder.image.layoutParams.apply { height = thumbH }
        holder.itemView.setOnClickListener { holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
        holder.itemView.setOnLongClickListener { v ->
            holder.bindingAdapterPosition.takeIf { it >= 0 }?.let { onLongClick(it, v) }
            true
        }

        val key = keyOf(page, position)
        holder.image.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            return
        }
        holder.image.setImageDrawable(null)
        scope.launch {
            val bmp = withContext(Dispatchers.Default) { render(page, position, thumbWidthPx, thumbH) }
            cache.put(key, bmp)
            if (holder.image.tag == key) holder.image.setImageBitmap(bmp)
        }
    }

    /** PDF 底图与页码绑定，所以 PDF 笔记的键里带上页码。 */
    private fun keyOf(page: Page, position: Int): String =
        "${page.id}:${System.identityHashCode(page.strokes)}:${page.template}:${page.backgroundColor}:" +
            if (pdfRenderer != null) position else ""

    private fun render(page: Page, index: Int, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val scale = w / page.width.coerceAtLeast(1).toFloat()
        val pdfBg = pdfRenderer?.renderThumbnail(index, bmp.width, bmp.height)
        if (pdfBg != null) {
            canvas.drawBitmap(pdfBg, 0f, 0f, null)
            pdfBg.recycle()
        }
        canvas.scale(scale, scale)
        val painter = StrokePainter()
        if (pdfBg == null) {
            PageTemplateRenderer.render(
                canvas, page.template, page.backgroundColor, page.width, page.height, minLine = 1f / scale
            )
        }
        painter.drawAll(canvas, page.strokes)
        return bmp
    }
}
