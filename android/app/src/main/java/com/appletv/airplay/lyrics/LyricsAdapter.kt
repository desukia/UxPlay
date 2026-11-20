package com.appletv.airplay.lyrics

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.appletv.airplay.R

class LyricsAdapter(
    private val context: Context,
    private val onLyricClick: ((position: Int, lyric: LyricLine) -> Unit)? = null
) : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

    private val items = mutableListOf<LyricLine>()
    private var activePosition: Int = -1
    private val sfProRounded: Typeface? = try {
        ResourcesCompat.getFont(context, R.font.sf_pro_rounded)
    } catch (e: Exception) {
        Typeface.DEFAULT_BOLD
    }

    fun submitList(newItems: List<LyricLine>) {
        items.clear()
        items.addAll(newItems)
        activePosition = -1
        notifyDataSetChanged()
    }

    fun setActivePosition(newPos: Int) {
        if (newPos == activePosition) return
        val prev = activePosition
        activePosition = newPos
        if (prev in items.indices) {
            notifyItemChanged(prev)
        }
        if (activePosition in items.indices) {
            notifyItemChanged(activePosition)
        }
    }

    fun getActivePosition(): Int = activePosition

    fun getItems(): List<LyricLine> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_apple_music_lyric, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, position == activePosition)
    }

    override fun getItemCount(): Int = items.size

    inner class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtLyricLine: TextView = itemView.findViewById(R.id.txtLyricLine)

        init {
            sfProRounded?.let {
                txtLyricLine.typeface = it
            }
            itemView.setOnClickListener {
                @Suppress("DEPRECATION")
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in items.indices) {
                    onLyricClick?.invoke(pos, items[pos])
                }
            }
        }

        fun bind(lyric: LyricLine, isActive: Boolean) {
            txtLyricLine.text = lyric.text
            txtLyricLine.pivotX = 0f
            txtLyricLine.pivotY = txtLyricLine.height.toFloat() / 2f

            if (isActive) {
                txtLyricLine.animate()
                    .alpha(1.0f)
                    .scaleX(1.06f)
                    .scaleY(1.06f)
                    .setDuration(220)
                    .start()
            } else {
                txtLyricLine.animate()
                    .alpha(0.35f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(220)
                    .start()
            }
        }
    }
}
