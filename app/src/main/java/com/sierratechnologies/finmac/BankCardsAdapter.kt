package com.sierratechnologies.finmac

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BankCardsAdapter(
    private val accountsList: List<Map<String, Any>>,
    private val onItemClick: (Map<String, Any>) -> Unit
) : RecyclerView.Adapter<BankCardsAdapter.BankCardViewHolder>() {

    class BankCardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val lblBankName: TextView = itemView.findViewById(R.id.lblCardBankName)
        val lblHolder: TextView = itemView.findViewById(R.id.lblCardHolder)
        val lblAccNo: TextView = itemView.findViewById(R.id.lblCardAccNo)
        val lblIfsc: TextView = itemView.findViewById(R.id.lblCardIfsc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BankCardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bank_card, parent, false)
        return BankCardViewHolder(view)
    }

    override fun onBindViewHolder(holder: BankCardViewHolder, position: Int) {
        val account = accountsList[position]

        val bankName = (account["bankName"] as? String)?.uppercase() ?: "BANK ACCOUNT"
        val holderName = account["accountHolder"] as? String ?: "--"
        val accNum = account["accountNumber"] as? String ?: "--"
        val ifsc = account["ifscCode"] as? String ?: "--"

        holder.lblBankName.text = bankName
        holder.lblHolder.text = "Holder: $holderName"
        holder.lblAccNo.text = "A/C No: $accNum"
        holder.lblIfsc.text = "IFSC: $ifsc"

        holder.itemView.setOnClickListener {
            onItemClick(account)
        }
    }

    override fun getItemCount(): Int = accountsList.size
}