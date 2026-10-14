package com.sierratechnologies.finmac

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView

class CommitteeAdapter(val items: ArrayList<HashMap<String, String>>) :
    RecyclerView.Adapter<CommitteeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val etRole: EditText = view.findViewById(R.id.etRole)
        val etName: EditText = view.findViewById(R.id.etName)
        val etContact: EditText = view.findViewById(R.id.etContact)
        val btnRemove: ImageButton = view.findViewById(R.id.btnRemoveRow)

        // Explicitly keep tracking pointers for active layout text watchers
        var roleWatcher: CustomTextWatcher? = null
        var nameWatcher: CustomTextWatcher? = null
        var contactWatcher: CustomTextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_comittee_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rowData = items[position]

        // 1. Remove old watchers to avoid recursive editing loop crashes when views recycle
        holder.roleWatcher?.let { holder.etRole.removeTextChangedListener(it) }
        holder.nameWatcher?.let { holder.etName.removeTextChangedListener(it) }
        holder.contactWatcher?.let { holder.etContact.removeTextChangedListener(it) }

        // 2. Hydrate text fields directly via map values
        holder.etRole.setText(rowData["role"] ?: "")
        holder.etName.setText(rowData["name"] ?: "")
        holder.etContact.setText(rowData["contact"] ?: "")

        // 3. Attach fresh text watchers updating the array instantly on every single keystroke
        holder.roleWatcher = CustomTextWatcher { text -> rowData["role"] = text }
        holder.nameWatcher = CustomTextWatcher { text -> rowData["name"] = text }
        holder.contactWatcher = CustomTextWatcher { text -> rowData["contact"] = text }

        holder.etRole.addTextChangedListener(holder.roleWatcher)
        holder.etName.addTextChangedListener(holder.nameWatcher)
        holder.etContact.addTextChangedListener(holder.contactWatcher)

        // 4. Row Removal Control Logic
        holder.btnRemove.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                items.removeAt(currentPos)
                notifyItemRemoved(currentPos)
            }
        }
    }

    override fun getItemCount() = items.size

    /**
     * A clean custom wrapper class to minimize duplicate boilerplates for text watching
     */
    class CustomTextWatcher(private val onTextChanged: (String) -> Unit) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onTextChanged(s?.toString()?.trim() ?: "")
        }
        override fun afterTextChanged(s: Editable?) {}
    }
}