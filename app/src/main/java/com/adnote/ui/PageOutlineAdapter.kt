package com.adnote.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.adnote.R
import com.adnote.model.Page

/**
 * 页面大纲与目录列表适配器，按页码顺序展示书签、手写识别或文字预览，支持快速跳转与书签编辑。
 */
class PageOutlineAdapter(
    private val onPageClick: (Int) -> Unit,
    private val onEditBookmarkClick: (Int) -> Unit,
) : RecyclerView.Adapter<PageOutlineAdapter.Holder>() {

    private var pages: List<Page> = emptyList()
    private var currentIndex: Int = 0

    fun submit(pages: List<Page>, currentIndex: Int) {
        this.pages = pages
        this.currentIndex = currentIndex
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_page_outline, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val page = pages[position]
        holder.bind(page, position, position == currentIndex)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvPageNum: TextView = view.findViewById(R.id.tvOutlinePageNum)
        private val tvBookmark: TextView = view.findViewById(R.id.tvOutlineBookmark)
        private val tvSnippet: TextView = view.findViewById(R.id.tvOutlineSnippet)
        private val tvCurrentBadge: TextView = view.findViewById(R.id.tvOutlineCurrentBadge)
        private val btnEditBookmark: Button = view.findViewById(R.id.btnOutlineEditBookmark)

        fun bind(page: Page, index: Int, isCurrent: Boolean) {
            tvPageNum.text = "第 ${index + 1} 页"
            tvCurrentBadge.visibility = if (isCurrent) View.VISIBLE else View.GONE

            val bookmark = page.bookmark
            if (!bookmark.isNullOrBlank()) {
                tvBookmark.text = "🔖 $bookmark"
                tvBookmark.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                btnEditBookmark.text = "编辑"
            } else {
                tvBookmark.text = "（无书签）"
                tvBookmark.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_tertiary))
                btnEditBookmark.text = "加书签"
            }

            val snippet = page.recognizedText?.trim()?.takeIf { it.isNotEmpty() }
                ?: page.texts.firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }
            if (!snippet.isNullOrBlank()) {
                tvSnippet.visibility = View.VISIBLE
                tvSnippet.text = snippet.replace('\n', ' ').take(40)
            } else {
                tvSnippet.visibility = View.GONE
            }

            btnEditBookmark.setOnClickListener {
                onEditBookmarkClick(index)
            }
            itemView.setOnClickListener {
                onPageClick(index)
            }
        }
    }
}
