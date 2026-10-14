package com.sierratechnologies.finmac

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class ModulesAdapter(
    private val modules: List<ModuleItem>,
    private val onModuleClick: ((ModuleItem) -> Unit)? = null
) : RecyclerView.Adapter<ModulesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgIcon: ImageView = view.findViewById(R.id.imgModuleIcon)
        val txtTitle: TextView = view.findViewById(R.id.txtModuleTitle)
        val txtDesc: TextView = view.findViewById(R.id.txtModuleDesc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_module, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val module = modules[position]

        // 1. Bind Title, Description, and Dynamic Icon
        holder.txtTitle.text = module.title
        holder.txtDesc.text = module.description
        holder.imgIcon.setImageResource(module.iconRes)

        // 2. Handle Item Click
        holder.itemView.setOnClickListener {
            val context = holder.itemView.context

            // If a click listener lambda was passed from Activity, delegate to it
            if (onModuleClick != null) {
                onModuleClick.invoke(module)
                return@setOnClickListener
            }

            // Fallback direct internal routing if lambda is not provided
            when {
                // Route to Society Overview / Profile
                module.title.equals("Society Layout", ignoreCase = true) ||
                        module.title.equals("Society Profile", ignoreCase = true) ||
                        module.title.equals("Society Overview", ignoreCase = true) -> {
                    val intent = Intent(context, SocietyOverviewActivity::class.java)
                    context.startActivity(intent)
                }

                // Route to Member Details Activity
                module.title.equals("Member Details", ignoreCase = true) ||
                        module.title.equals("Members", ignoreCase = true) ||
                        module.title.equals("Member Registry", ignoreCase = true) -> {
                    val intent = Intent(context, MemberDetailsActivity::class.java)
                    context.startActivity(intent)
                }

                // Route to Bank Details Activity
                module.title.equals("Bank Details", ignoreCase = true) ||
                        module.title.equals("Bank", ignoreCase = true) ||
                        module.title.equals("Banking Details", ignoreCase = true) -> {
                    val intent = Intent(context, BankDetailsActivity::class.java)
                    context.startActivity(intent)
                }

                // Route to Vendor Details Activity
                module.title.equals("Vendor Details", ignoreCase = true) ||
                        module.title.equals("Vendor", ignoreCase = true) ||
                        module.title.equals("View Vendor Details", ignoreCase = true) -> {
                    val intent = Intent(context, VendorDetailsActivity::class.java)
                    context.startActivity(intent)
                }

                // Route to Entry Book / Voucher Transaction Activity
                module.title.equals("Entry Book", ignoreCase = true) ||
                        module.title.equals("Entry", ignoreCase = true) ||
                        module.title.equals("Transaction Entry Details", ignoreCase = true) -> {
                    val intent = Intent(context, VoucherTransactionActivity::class.java)
                    context.startActivity(intent)
                }

                // Route to Cash Book Activity
                module.title.equals("Cash Book", ignoreCase = true) ||
                        module.title.equals("Cash", ignoreCase = true) ||
                        module.title.equals("Cashbook Ledger", ignoreCase = true) -> {
                    val intent = Intent(context, CashbookActivity::class.java)
                    context.startActivity(intent)
                }

                // Route to Ledger Account Activity
                module.title.equals("Ledger Account", ignoreCase = true) ||
                        module.title.equals("Ledger", ignoreCase = true) ||
                        module.title.equals("Account Ledger", ignoreCase = true) -> {
                    val intent = Intent(context, LedgerAccountActivity::class.java)
                    context.startActivity(intent)
                }

                // Route to Trial Balance Activity
                module.title.equals("Trial Balance", ignoreCase = true) ||
                        module.title.equals("Trial", ignoreCase = true) ||
                        module.title.equals("Trial Ledger", ignoreCase = true) -> {
                    val intent = Intent(context, TrialBalanceActivity::class.java)
                    context.startActivity(intent)
                }

                // Route to Balance Sheet Activity
                module.title.equals("Balance Sheet", ignoreCase = true) ||
                        module.title.equals("Balance", ignoreCase = true) ||
                        module.title.equals("Balance Flow", ignoreCase = true) -> {
                    val intent = Intent(context, BalanceSheetActivity::class.java)
                    context.startActivity(intent)
                }

                module.title.equals("Admin Management", ignoreCase = true) ||
                        module.title.equals("Admin", ignoreCase = true) ||
                        module.title.equals("Admin Manage", ignoreCase = true) -> {
                    val intent = Intent(context, AdminManagementActivity::class.java)
                    context.startActivity(intent)
                }

                // Generic fallback for unhandled modules
                else -> {
                    Toast.makeText(context, "${module.title} clicked", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun getItemCount(): Int = modules.size
}