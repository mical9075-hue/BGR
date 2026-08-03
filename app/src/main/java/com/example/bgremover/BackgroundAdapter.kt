package com.example.bgremover

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class BackgroundAdapter(
    private var items: List<BackgroundOption>,
    private val onClick: (BackgroundOption) -> Unit
) : RecyclerView.Adapter<BackgroundAdapter.SwatchViewHolder>() {

    class SwatchViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: android.widget.ImageView = view.findViewById(R.id.swatchImage)
        val icon: android.widget.ImageView = view.findViewById(R.id.swatchIcon)
        val loading: android.widget.ProgressBar = view.findViewById(R.id.swatchLoading)
    }

    fun submitList(newItems: List<BackgroundOption>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SwatchViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_background_swatch, parent, false)
        return SwatchViewHolder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: SwatchViewHolder, position: Int) {
        val item = items[position]
        holder.icon.visibility = View.GONE
        holder.loading.visibility = View.GONE
        holder.image.setImageDrawable(null)
        holder.image.visibility = View.VISIBLE

        when (item) {
            is BackgroundOption.SolidColor -> {
                holder.image.setImageDrawable(null)
                holder.image.setBackgroundColor(item.color)
            }

            is BackgroundOption.Gradient -> {
                holder.image.background = null
                val gd = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(item.startColor, item.endColor)
                )
                holder.image.setImageDrawable(null)
                holder.image.background = gd
            }

            is BackgroundOption.OnlineImage -> {
                holder.image.setBackgroundColor(0xFFE7E0EC.toInt())
                holder.loading.visibility = View.VISIBLE
                Glide.with(holder.image.context)
                    .load(item.thumbUrl)
                    .centerCrop()
                    .into(holder.image)
                holder.loading.visibility = View.GONE
            }

            BackgroundOption.PickFromGallery -> {
                holder.image.setBackgroundColor(0xFFE8DEF8.toInt())
                holder.icon.visibility = View.VISIBLE
                holder.icon.setImageResource(R.drawable.ic_gallery)
                holder.icon.setColorFilter(0xFF6750A4.toInt())
            }

            BackgroundOption.Transparent -> {
                holder.image.setImageResource(R.drawable.checkerboard_bg)
                holder.image.background = null
            }
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }
}
