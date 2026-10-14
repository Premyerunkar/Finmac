package com.sierratechnologies.finmac

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AppCache {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private const val PREF_NAME = "Finmac_AppCache_Disk"

    // In-memory cache structures
    var branches: List<BranchDto>? = null
    val membersMap = mutableMapOf<String, List<MemberDto>>()
    val vendorsMap = mutableMapOf<String, List<VendorDto>>()
    val bankAccountsMap = mutableMapOf<String, List<BankAccountDto>>()
    val receiptsMap = mutableMapOf<String, List<ReceiptDto>>()
    val paymentsMap = mutableMapOf<String, List<PaymentDto>>()
    val contraMap = mutableMapOf<String, List<ContraEntryDto>>()
    val categoriesMap = mutableMapOf<String, List<AccountCategoryDto>>()

    // Generic disk storage helpers
    fun saveToDisk(context: Context, key: String, dataJson: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(key, dataJson).apply()
    }

    fun getFromDisk(context: Context, key: String): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(key, null)
    }

    // Typed disk cache helpers for Members
    fun saveMembers(context: Context, branchCode: String, list: List<MemberDto>) {
        membersMap[branchCode] = list
        try {
            saveToDisk(context, "members_$branchCode", json.encodeToString(list))
        } catch (_: Exception) {}
    }

    fun getMembers(context: Context, branchCode: String): List<MemberDto>? {
        membersMap[branchCode]?.let { return it }
        val raw = getFromDisk(context, "members_$branchCode") ?: return null
        return try {
            val list = json.decodeFromString<List<MemberDto>>(raw)
            membersMap[branchCode] = list
            list
        } catch (_: Exception) {
            null
        }
    }

    // Typed disk cache helpers for Vendors
    fun saveVendors(context: Context, branchCode: String, list: List<VendorDto>) {
        vendorsMap[branchCode] = list
        try {
            saveToDisk(context, "vendors_$branchCode", json.encodeToString(list))
        } catch (_: Exception) {}
    }

    fun getVendors(context: Context, branchCode: String): List<VendorDto>? {
        vendorsMap[branchCode]?.let { return it }
        val raw = getFromDisk(context, "vendors_$branchCode") ?: return null
        return try {
            val list = json.decodeFromString<List<VendorDto>>(raw)
            vendorsMap[branchCode] = list
            list
        } catch (_: Exception) {
            null
        }
    }

    // Typed disk cache helpers for Bank Accounts
    fun saveBankAccounts(context: Context, branchCode: String, list: List<BankAccountDto>) {
        bankAccountsMap[branchCode] = list
        try {
            saveToDisk(context, "banks_$branchCode", json.encodeToString(list))
        } catch (_: Exception) {}
    }

    fun getBankAccounts(context: Context, branchCode: String): List<BankAccountDto>? {
        bankAccountsMap[branchCode]?.let { return it }
        val raw = getFromDisk(context, "banks_$branchCode") ?: return null
        return try {
            val list = json.decodeFromString<List<BankAccountDto>>(raw)
            bankAccountsMap[branchCode] = list
            list
        } catch (_: Exception) {
            null
        }
    }

    // Typed disk cache helpers for Receipts
    fun saveReceipts(context: Context, branchCode: String, list: List<ReceiptDto>) {
        receiptsMap[branchCode] = list
        try {
            saveToDisk(context, "receipts_$branchCode", json.encodeToString(list))
        } catch (_: Exception) {}
    }

    fun getReceipts(context: Context, branchCode: String): List<ReceiptDto>? {
        receiptsMap[branchCode]?.let { return it }
        val raw = getFromDisk(context, "receipts_$branchCode") ?: return null
        return try {
            val list = json.decodeFromString<List<ReceiptDto>>(raw)
            receiptsMap[branchCode] = list
            list
        } catch (_: Exception) {
            null
        }
    }

    // Typed disk cache helpers for Payments
    fun savePayments(context: Context, branchCode: String, list: List<PaymentDto>) {
        paymentsMap[branchCode] = list
        try {
            saveToDisk(context, "payments_$branchCode", json.encodeToString(list))
        } catch (_: Exception) {}
    }

    fun getPayments(context: Context, branchCode: String): List<PaymentDto>? {
        paymentsMap[branchCode]?.let { return it }
        val raw = getFromDisk(context, "payments_$branchCode") ?: return null
        return try {
            val list = json.decodeFromString<List<PaymentDto>>(raw)
            paymentsMap[branchCode] = list
            list
        } catch (_: Exception) {
            null
        }
    }

    // Typed disk cache helpers for Contra
    fun saveContra(context: Context, branchCode: String, list: List<ContraEntryDto>) {
        contraMap[branchCode] = list
        try {
            saveToDisk(context, "contra_$branchCode", json.encodeToString(list))
        } catch (_: Exception) {}
    }

    fun getContra(context: Context, branchCode: String): List<ContraEntryDto>? {
        contraMap[branchCode]?.let { return it }
        val raw = getFromDisk(context, "contra_$branchCode") ?: return null
        return try {
            val list = json.decodeFromString<List<ContraEntryDto>>(raw)
            contraMap[branchCode] = list
            list
        } catch (_: Exception) {
            null
        }
    }

    // Typed disk cache helpers for Branches
    fun saveBranches(context: Context, list: List<BranchDto>) {
        branches = list
        try {
            saveToDisk(context, "branches_master", json.encodeToString(list))
        } catch (_: Exception) {}
    }

    fun getBranches(context: Context): List<BranchDto>? {
        branches?.let { return it }
        val raw = getFromDisk(context, "branches_master") ?: return null
        return try {
            val list = json.decodeFromString<List<BranchDto>>(raw)
            branches = list
            list
        } catch (_: Exception) {
            null
        }
    }
}