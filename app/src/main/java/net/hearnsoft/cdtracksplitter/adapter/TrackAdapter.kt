package net.hearnsoft.cdtracksplitter.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.hearnsoft.cdtracksplitter.R
import net.hearnsoft.cdtracksplitter.model.TrackItem

class TrackAdapter(
    private val tracks: List<TrackItem>,
    private val onTrackClick: (TrackItem) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val trackNumber: TextView = itemView.findViewById(R.id.trackNumber)
        private val trackTitle: TextView = itemView.findViewById(R.id.trackTitle)
        private val trackTime: TextView = itemView.findViewById(R.id.trackTime)

        fun bind(track: TrackItem) {
            trackNumber.text = String.format("%02d", track.number)
            trackTitle.text = track.title
            trackTime.text = track.indexTime

            itemView.setOnClickListener {
                onTrackClick(track)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(tracks[position])
    }

    override fun getItemCount(): Int = tracks.size
}