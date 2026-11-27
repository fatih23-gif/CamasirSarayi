package com.camasirsarayi.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.camasirsarayi.app.databinding.ItemRowBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val navy = Color.rgb(24, 52, 86)
internal val lightSlate = Color.rgb(237, 242, 248)
internal val green = Color.rgb(221, 246, 235)
internal val amber = Color.rgb(255, 239, 219)
internal val pink = Color.rgb(252, 232, 235)
internal val navyTitle = Color.rgb(20, 44, 80)

internal fun EditText.formError(message: String) {
    val box = parent?.parent as? TextInputLayout
    if (box != null) box.error = message else error = message
    requestFocus()
}

internal fun MainActivity.dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()
internal fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    stroke?.let { setStroke(1, it) }
}
internal fun MainActivity.text(value: String, size: Int = 14, color: Int = getColor(R.color.ink)): TextView =
    TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color) }

internal fun MainActivity.add(parent: LinearLayout, child: View, margin: Int = 10) {
    parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(margin) })
}

internal fun MainActivity.heading(title: String, subtitle: String = "", parent: LinearLayout = ui.content) {
    add(parent, text(title, 26), 5)
    if (subtitle.isNotBlank()) add(parent, text(subtitle, 14, getColor(R.color.muted)), 22)
}

internal fun MainActivity.center(value: String, size: Int = 14, color: Int = getColor(R.color.muted), parent: LinearLayout = ui.content) {
    add(parent, text(value, size, color).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) })
}

internal fun MainActivity.icon(res: Int, size: Int = 90, parent: LinearLayout = ui.content, tint: Int? = null) {
    val image = ImageView(this).apply {
        setImageResource(res)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        tint?.let { imageTintList = ColorStateList.valueOf(it) }
    }
    parent.addView(image, LinearLayout.LayoutParams(dp(size), dp(size)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(14)
        bottomMargin = dp(12)
    })
}

internal fun MainActivity.notice(value: String, color: Int = lightSlate, parent: LinearLayout = ui.content) {
    add(parent, text(value, 13, if (color == pink) Color.rgb(147, 26, 50) else navyTitle).apply {
        background = rounded(color, dp(16).toFloat())
        setPadding(dp(15), dp(14), dp(15), dp(14))
    }, 14)
}

internal fun MainActivity.button(label: String, fill: Int = navy, fg: Int = Color.WHITE,
    parent: LinearLayout = ui.content, online: Boolean = false, click: () -> Unit): MaterialButton {
    val button = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(52)
        cornerRadius = dp(15)
        backgroundTintList = ColorStateList.valueOf(fill)
        setTextColor(fg)
        setOnClickListener {
            if (online && model.offline) actionFeedback("İşlem için internet bağlantısı gerekiyor.") else click()
        }
    }
    add(parent, button)
    return button
}

internal fun MainActivity.field(label: String, hint: String = "", value: String = "", password: Boolean = false,
    number: Boolean = false, email: Boolean = false, multiline: Boolean = false,
    radius: Int = 14, height: Int = 52, picker: (() -> Unit)? = null): EditText {
    val labelView = text(label, 13)
    add(ui.content, labelView, 5)
    val box = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
        isHintEnabled = false
        val corners = dp(radius).toFloat()
        setBoxCornerRadii(corners, corners, corners, corners)
        setBoxBackgroundColor(getColor(R.color.card))
        if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        if (picker != null) {
            endIconMode = TextInputLayout.END_ICON_CUSTOM
            setEndIconDrawable(R.drawable.ic_expand)
            endIconContentDescription = label
            setEndIconOnClickListener { picker() }
        }
    }
    val input = TextInputEditText(box.context).apply {
        inputType = when {
            password -> 129
            number -> 2
            email -> 33
            multiline -> 131073
            else -> 1
        }
        id = View.generateViewId()
        labelView.labelFor = id
        this.hint = hint
        minHeight = dp(height)
        if (picker != null) {
            keyListener = null
            isFocusable = false
            setOnClickListener { picker() }
        }
        if (multiline) gravity = Gravity.TOP or Gravity.START
        setText(value)
        if (multiline) minLines = 3 else maxLines = 1
        doAfterTextChanged {
            box.error = null
            model.editing = true
        }
    }
    box.addView(input, LinearLayout.LayoutParams(-1, -2))
    add(ui.content, box, 12)
    return input
}

internal fun MainActivity.section(title: String, link: String = "", parent: LinearLayout = ui.content, action: () -> Unit = {}) {
    val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(36) }
    line.addView(text(title, 16), LinearLayout.LayoutParams(0, -2, 1f))
    if (link.isNotBlank()) line.addView(text(link, 12, getColor(R.color.muted)).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(8), 0, 0, 0)
        setOnClickListener { action() }
    })
    add(parent, line, 4)
}

internal data class Row(val name: String, val detail: String, val icon: Int = R.drawable.ic_machine,
    val badge: String = "", val color: Int = lightSlate, val identity: String? = null, val click: (() -> Unit)? = null)

internal fun MainActivity.rows(items: List<Row>, parent: LinearLayout = ui.content, empty: String = "Henüz kayıt yok.", compact: Boolean = false) {
    if (items.isEmpty()) {
        notice(empty, parent = parent)
        return
    }
    if (compact) {
        for (item in items) {
            val binding = ItemRowBinding.inflate(layoutInflater, parent, false)
            bindRow(binding, item)
            add(parent, binding.root, 8)
        }
        return
    }
    add(parent, RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@rows)
        isNestedScrollingEnabled = false
        adapter = RowAdapter().also { it.submitList(items) }
    }, 3)
}

internal class RowAdapter : ListAdapter<Row, RowAdapter.Holder>(object : DiffUtil.ItemCallback<Row>() {
    override fun areItemsTheSame(old: Row, new: Row) = old.identity != null && old.identity == new.identity ||
        old.identity == null && new.identity == null && old.name == new.name && old.detail == new.detail
    override fun areContentsTheSame(old: Row, new: Row) = old == new
}) {
    class Holder(val binding: ItemRowBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val binding = ItemRowBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
        val margin = (8 * parent.resources.displayMetrics.density).toInt()
        binding.root.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = margin }
        return Holder(binding)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) = bindRow(holder.binding, getItem(position))
}

private fun bindRow(binding: ItemRowBinding, row: Row) {
    with(binding) {
        name.text = row.name
        detail.text = row.detail
        detail.isVisible = row.detail.isNotBlank()
        icon.setImageResource(row.icon)
        val tint = when (row.color) {
            green -> Color.rgb(0, 126, 94)
            amber -> Color.rgb(177, 92, 0)
            pink -> Color.rgb(192, 28, 54)
            else -> navy
        }
        icon.imageTintList = ColorStateList.valueOf(tint)
        icon.background = rounded(row.color, icon.resources.displayMetrics.density * 12)
        badge.isVisible = row.badge.isNotBlank()
        badge.text = row.badge
        badge.setTextColor(tint)
        badge.background = rounded(row.color, icon.resources.displayMetrics.density * 8)
        chevron.isVisible = row.click != null
        root.setOnClickListener(if (row.click == null) null else View.OnClickListener { row.click.invoke() })
    }
}

internal fun MainActivity.stats(values: List<Triple<String, String, Int>>, parent: LinearLayout = ui.content) {
    val line = LinearLayout(this)
    for ((index, triple) in values.withIndex()) {
        val (value, title, fill) = triple
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(76)
            background = rounded(fill, dp(15).toFloat(), getColor(R.color.line))
            addView(text(value, if (values.size > 4) 19 else 25, navyTitle).apply { gravity = Gravity.CENTER })
            addView(text(title, 11, navyTitle).apply { gravity = Gravity.CENTER })
        }
        line.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(7) })
    }
    add(parent, line, 12)
}

internal fun MainActivity.message(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
internal fun MainActivity.confirm(title: String, description: String, action: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(description)
        .setNegativeButton("Vazgeç", null).setPositiveButton("Onayla") { _, _ -> action() }.show()
}
internal fun MainActivity.choose(title: String, values: List<String>, action: (Int) -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setItems(values.toTypedArray()) { _, position -> action(position) }.show()
}
internal fun clock(time: Long): String = if (time <= 0) "—" else SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(time))
internal fun date(time: Long): String = if (time <= 0) "—" else SimpleDateFormat("d MMM yyyy · HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(time))
internal fun countdownText(milliseconds: Long): String {
    val seconds = ((milliseconds + 999).coerceAtLeast(0)) / 1000
    return if (seconds >= 3600) "%02d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}
