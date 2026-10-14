package com.sierratechnologies.finmac

object ModuleRepository {
    fun getAllModules(): List<ModuleItem> {
        return listOf(
            ModuleItem("Society Layout", "Society Overview", R.drawable.societylayout),
            ModuleItem("Member Details", "View Member Details", R.drawable.memberdetails),
            ModuleItem("Entry Book", "Entry Book Description", R.drawable.entrybook),
            ModuleItem("Member Outstanding", "Member outstanding amount", R.drawable.memberoutstanding),
            ModuleItem("Cash Book", "View All Cash & Payment Entries", R.drawable.cashbook),
            ModuleItem("Ledger Account", "Ledger Account Description", R.drawable.ledgeraccount),
            ModuleItem("Trial Balance", "View All Trial Balances Entries", R.drawable.trialbalance),
            ModuleItem("Bank Details", "View Banking Details", R.drawable.bankdetails),
            ModuleItem("P & L Accounts", "P and L Accounts", R.drawable.placcounts),
            ModuleItem("Balance Sheet", "Balance Sheet Description", R.drawable.balancesheet),
            ModuleItem("Share Capital", "Share Capital Description", R.drawable.sharecapital),
            ModuleItem("Vendor Details", "View Vendor Records", R.drawable.vendordetails),
            ModuleItem("Investment List", "Overview of Investment List", R.drawable.investmentlist),
            ModuleItem("I & J Registry", "Register showing ownership", R.drawable.ijregistry),
            ModuleItem("Outstanding List", "Unpaid dues by members", R.drawable.outstandinglist),
            ModuleItem("Asset List", "Physical property owned by the society", R.drawable.assetlist),
            ModuleItem("Minutes Of Meeting", "Minutes Of Meeting", R.drawable.mom),
            ModuleItem("Audit analysis", "Graphical Representation of Audit Reports", R.drawable.audianalysis),
            ModuleItem("Admin Management", "Admin Users Management", R.drawable.adminmgmt)
        )
    }
}