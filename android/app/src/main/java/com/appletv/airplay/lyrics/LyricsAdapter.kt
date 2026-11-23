package com.appletv.airplay.lyrics

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
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

        // Refresh range around previous and new position so depth of field blur and scale update smoothly
        val minPos = (minOf(prev, newPos) - 4).coerceAtLeast(0)
        val maxPos = (maxOf(prev, newPos) + 5).coerceAtMost(items.size - 1)
        if (maxPos >= minPos) {
            notifyItemRangeChanged(minPos, maxPos - minPos + 1)
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
        holder.bind(item, position, activePosition)
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

        fun bind(lyric: LyricLine, position: Int, currentActive: Int) {
            txtLyricLine.text = lyric.text
            txtLyricLine.pivotX = 0f
            txtLyricLine.pivotY = 24f

            if (currentActive == -1) {
                txtLyricLine.animate().cancel()
                txtLyricLine.alpha = 0.35f
                txtLyricLine.scaleX = 1.0f
                txtLyricLine.scaleY = 1.0f
                clearBlur()
                return
            }

            val diff = position - currentActive
            when {
                diff == 0 -> {
                    // Active line: Bright pure white, enlarged, crystal clear
                    txtLyricLine.animate()
                        .alpha(1.0f)
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .setDuration(240)
                        .start()
                    clearBlur()
                }
                diff == -1 -> {
                    // Line above: Fading into the past
                    txtLyricLine.animate()
                        .alpha(0.18f)
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(240)
                        .start()
                    applyBlur(2.5f)
                }
                diff < -1 -> {
                    // Lines further above: Almost completely faded
                    txtLyricLine.animate()
                        .alpha(0.08f)
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .setDuration(240)
                        .start()
                    applyBlur(5.0f)
                }
                diff == 1 -> {
                    // Line 1 below: Dimmed white ~35%
                    txtLyricLine.animate()
                        .alpha(0.35f)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(240)
                        .start()
                    clearBlur()
                }
                diff == 2 -> {
                    // Line 2 below: White ~24% with radius 2pt blur
                    txtLyricLine.animate()
                        .alpha(0.24f)
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(240)
                        .start()
                    applyBlur(2.0f)
                }
                diff == 3 -> {
                    // Line 3 below: White ~15% with radius 4pt blur
                    txtLyricLine.animate()
                        .alpha(0.15f)
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .setDuration(240)
                        .start()
                    applyBlur(4.0f)
                }
                else -> {
                    // Line 4+ below: White ~8% with radius 6pt blur
                    txtLyricLine.animate()
                        .alpha(0.08f)
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(240)
                        .start()
                    applyBlur(6.0f)
                }
            }
        }

        private fun applyBlur(radius: Float) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val effect = RenderEffect.createBlurEffect(radius * 2.5f, radius * 2.5f, Shader.TileMode.CLAMP)
                    txtLyricLine.setRenderEffect(effect)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        private fun clearBlur() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    txtLyricLine.setRenderEffect(null)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
}
