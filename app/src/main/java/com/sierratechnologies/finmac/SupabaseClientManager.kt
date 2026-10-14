package com.sierratechnologies.finmac

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object SupabaseClientManager {
    val client by lazy {
        createSupabaseClient(
            supabaseUrl = Util.SUPABASE_URL,
            supabaseKey = Util.SUPABASE_ANON_KEY
        ) {
            install(Postgrest)
        }
    }

    val postgrest by lazy {
        client.postgrest
    }
}

@Serializable
data class BranchDto(
    @SerialName("code") val code: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("division") val division: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("username") val username: String = "",
    @SerialName("password") val password: String = "",
    @SerialName("registration_no") val registrationNo: String? = "REG-MH-26/X4911",
    @SerialName("total_flats") val totalFlats: String? = "",
    @SerialName("committee_period") val committeePeriod: String? = "",
    @SerialName("committee_elected_on") val committeeElectedOn: String? = "",
    @SerialName("cloud_pdf_base64") val cloudPdfBase64: String? = "",
    @SerialName("committee_list") val committeeList: List<CommitteeMemberDto>? = emptyList()
)

@Serializable
data class CommitteeMemberDto(
    @SerialName("role") val role: String? = "",
    @SerialName("name") val name: String? = "",
    @SerialName("contact") val contact: String? = ""
)

@Serializable
data class BinBranchDto(
    @SerialName("code") val code: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("division") val division: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("password") val password: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class StaffUserDto(
    @SerialName("id") val id: String? = null,
    @SerialName("branch_code") val branchCode: String = "",
    @SerialName("username") val username: String = "",
    @SerialName("password") val password: String = "",
    @SerialName("full_name") val fullName: String = "",
    @SerialName("contact_no") val contactNo: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("remarks") val remarks: String? = null
)

@Serializable
data class NomineeDto(
    @SerialName("name") val name: String = "",
    @SerialName("share") val share: String = "",
    @SerialName("contact") val contact: String = "",
    @SerialName("address") val address: String = ""
)

@Serializable
data class MemberDto(
    @SerialName("id") val id: String? = null,
    @SerialName("branch_code") val branchCode: String = "",
    @SerialName("flat_no_id") val flatNoId: String = "",
    @SerialName("member_name") val memberName: String = "",
    @SerialName("co_owner_name") val coOwnerName: String? = "",
    @SerialName("contact_no") val contactNo: String? = "",
    @SerialName("area") val area: String? = "",
    @SerialName("corr_address") val corrAddress: String? = "",
    @SerialName("perm_address") val permAddress: String? = "",
    @SerialName("shares") val shares: String? = "0",
    @SerialName("corr_amt") val corrAmt: String? = "",
    @SerialName("shares_ii") val sharesIi: String? = "",
    @SerialName("contri_amt") val contriAmt: String? = "",
    @SerialName("nominees_list") val nomineesList: List<NomineeDto>? = emptyList()
)

@Serializable
data class VendorDto(
    @SerialName("id") val id: String? = null,
    @SerialName("branch_code") val branchCode: String = "",
    @SerialName("vendor_code") val vendorCode: String = "",
    @SerialName("party_name") val partyName: String = "",
    @SerialName("category_head") val categoryHead: String = "",
    @SerialName("contact_no") val contactNo: String? = "",
    @SerialName("pan_no") val panNo: String? = "N/A",
    @SerialName("aadhaar_identity") val aadhaarIdentity: String? = "N/A"
)

@Serializable
data class BankAccountDto(
    @SerialName("id") val id: String? = null,
    @SerialName("branch_code") val branchCode: String = "",
    @SerialName("account_number") val accountNumber: String = "",
    @SerialName("bank_name") val bankName: String = "",
    @SerialName("account_holder") val accountHolder: String = "",
    @SerialName("ifsc_code") val ifscCode: String = "",
    @SerialName("current_balance") val currentBalance: Double = 0.0
)

@Serializable
data class ReceiptDto(
    @SerialName("id") val id: String? = null,
    @SerialName("branch_code") val branchCode: String = "",
    @SerialName("invoice_no") val invoiceNo: String = "",
    @SerialName("date") val date: String = "",
    @SerialName("flat_no") val flatNo: String? = "",
    @SerialName("party_name") val partyName: String = "",
    @SerialName("category_head") val categoryHead: String = "",
    @SerialName("account_group") val accountGroup: String? = "INCOME",
    @SerialName("custom_narration") val customNarration: String? = "",
    @SerialName("payment_mode") val paymentMode: String? = "Cash",
    @SerialName("selected_bank") val selectedBank: String? = "N/A",
    @SerialName("linked_account_no") val linkedAccountNo: String? = "N/A",
    @SerialName("base_amount") val baseAmount: Double = 0.0,
    @SerialName("fine_amount") val fineAmount: Double = 0.0,
    @SerialName("total_final_amount") val totalFinalAmount: Double = 0.0,
    @SerialName("operator_account_identity") val operatorAccountIdentity: String? = "Admin",
    @SerialName("rejection_or_change_reason") val rejectionOrChangeReason: String? = ""
)

@Serializable
data class PaymentDto(
    @SerialName("id") val id: String? = null,
    @SerialName("branch_code") val branchCode: String = "",
    @SerialName("invoice_no") val invoiceNo: String = "",
    @SerialName("date") val date: String = "",
    @SerialName("vendor_code") val vendorCode: String? = "",
    @SerialName("party_name") val partyName: String = "",
    @SerialName("category_head") val categoryHead: String = "",
    @SerialName("account_group") val accountGroup: String? = "EXPENSE",
    @SerialName("custom_narration") val customNarration: String? = "",
    @SerialName("payment_mode") val paymentMode: String? = "Cash",
    @SerialName("selected_bank") val selectedBank: String? = "N/A",
    @SerialName("linked_account_no") val linkedAccountNo: String? = "N/A",
    @SerialName("base_amount") val baseAmount: Double = 0.0,
    @SerialName("fine_amount") val fineAmount: Double = 0.0,
    @SerialName("total_final_amount") val totalFinalAmount: Double = 0.0,
    @SerialName("operator_account_identity") val operatorAccountIdentity: String? = "Admin",
    @SerialName("rejection_or_change_reason") val rejectionOrChangeReason: String? = ""
)

@Serializable
data class ContraEntryDto(
    @SerialName("id") val id: String? = null,
    @SerialName("branch_code") val branchCode: String = "",
    @SerialName("voucher_no") val voucherNo: String = "",
    @SerialName("date") val date: String = "",
    @SerialName("flat_no") val flatNo: String? = "",
    @SerialName("party_name") val partyName: String = "",
    @SerialName("source_account") val sourceAccount: String = "",
    @SerialName("destination_account") val destinationAccount: String = "",
    @SerialName("amount") val amount: Double = 0.0,
    @SerialName("narration") val narration: String? = "",
    @SerialName("operator_identity") val operatorIdentity: String? = "Admin",
    @SerialName("rejection_or_change_reason") val rejectionOrChangeReason: String? = ""
)

@Serializable
data class OpeningBalanceDto(
    @SerialName("id") val id: String? = null,
    @SerialName("branch_code") val branchCode: String = "",
    @SerialName("financial_year") val financialYear: String = "",
    @SerialName("opening_cash_balance") val openingCashBalance: Double = 0.0,
    @SerialName("opening_bank_balance") val openingBankBalance: Double = 0.0,
    @SerialName("recorded_by") val recordedBy: String? = "Admin"
)

@Serializable
data class VoucherAuditLogDto(
    @SerialName("id") val id: String? = null,
    @SerialName("branch_code") val branchCode: String = "",
    @SerialName("invoice_no") val invoiceNo: String = "",
    @SerialName("modified_by") val modifiedBy: String = "",
    @SerialName("timestamp") val timestamp: String = "",
    @SerialName("reason_note") val reasonNote: String? = "",
    @SerialName("access_restriction_tier") val accessRestrictionTier: String? = "SUPERADMIN_ONLY"
)

@Serializable
data class AccountCategoryDto(
    @SerialName("id") val id: String? = null,
    @SerialName("branch_code") val branchCode: String = "",
    @SerialName("category_head") val categoryHead: String = "",
    @SerialName("account_group") val accountGroup: String = "",
    @SerialName("voucher_type") val voucherType: String = "ALL"
)