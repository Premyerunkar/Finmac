package com.sierratechnologies.finmac

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class VendorCardsAdapter(
    private val vendorList: List<Map<String, Any>>,
    private val onItemClick: (Map<String, Any>) -> Unit
) : RecyclerView.Adapter<VendorCardsAdapter.VendorViewHolder>() {

    class VendorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val lblName: TextView = itemView.findViewById(R.id.lblVendorName)
        val lblCode: TextView = itemView.findViewById(R.id.lblVendorCode)
        val lblHead: TextView = itemView.findViewById(R.id.lblAccountHead)
        val lblContact: TextView = itemView.findViewById(R.id.lblContact)
        val lblPan: TextView = itemView.findViewById(R.id.lblPan)
        val lblAadhaar: TextView = itemView.findViewById(R.id.lblAadhaar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VendorViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vendor_card, parent, false)
        return VendorViewHolder(view)
    }

    override fun onBindViewHolder(holder: VendorViewHolder, position: Int) {
        val vendor = vendorList[position]

        holder.lblName.text = (vendor["partyName"] as? String ?: "VENDOR").uppercase()
        holder.lblCode.text = "Code: " + (vendor["vendorCode"] as? String ?: "--")
        holder.lblHead.text = "Head: " + (vendor["categoryHead"] as? String ?: "--")
        holder.lblContact.text = "Contact: " + (vendor["contactNo"] as? String ?: "--")
        holder.lblPan.text = "PAN: " + (vendor["panNo"] as? String ?: "--")

        // Redact delicate identification tokens
        holder.lblAadhaar.text = "Aadhaar Identity: " + (vendor["aadhaarIdentity"] as? String ?: "[Aadhaar Redacted]")

        holder.itemView.setOnClickListener { onItemClick(vendor) }
    }

    override fun getItemCount(): Int = vendorList.size
}