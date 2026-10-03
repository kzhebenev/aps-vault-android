package ru.devkz.apsvault.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.devkz.apsvault.R
import ru.devkz.apsvault.SecretItem
import ru.devkz.apsvault.Vault

/**
 * System autofill. Values are never put into the response up front: every dataset carries
 * only a name and opens AutofillAuthActivity, which reads that one secret when the person
 * picks it (contract, section 4: a read is audited, do not prefetch values).
 */
class VaultAutofillService : AutofillService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onFillRequest(request: FillRequest, cancel: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val form = FormParser.parse(structure)
        if (form.isEmpty || form.packageName == packageName || Vault.base(this) == null) return callback.onSuccess(null)

        if (!Vault.unlocked(this)) {
            val sender = AutofillAuthActivity.sender(this, AutofillAuthActivity.MODE_UNLOCK, form, null)
            val resp = FillResponse.Builder()
                .setAuthentication(form.ids.toTypedArray(), sender, presentation(this, getString(R.string.af_unlock), null))
                .build()
            return callback.onSuccess(resp)
        }
        val job = scope.launch {
            val list = runCatching { Vault.current(this@VaultAutofillService)!!.list() }.getOrNull()
            if (list == null) { callback.onSuccess(null); return@launch }
            Vault.touch()
            callback.onSuccess(response(this@VaultAutofillService, form, FormParser.matches(form, list)))
        }
        cancel.setOnCancelListener { job.cancel() }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        /** Matching secrets first, then an entry that opens a search over the whole vault. */
        fun response(ctx: Context, form: Form, matches: List<SecretItem>): FillResponse {
            val b = FillResponse.Builder()
            for (s in matches.filter { !it.requireApproval }.take(8)) {
                b.addDataset(dataset(form, presentation(ctx, s.name, listOf(s.folderName, s.host).filter { it.isNotBlank() }.joinToString(" · ")),
                    AutofillAuthActivity.sender(ctx, AutofillAuthActivity.MODE_DATASET, form, s.id)))
            }
            b.addDataset(dataset(form, presentation(ctx, ctx.getString(R.string.af_search), null),
                AutofillAuthActivity.sender(ctx, AutofillAuthActivity.MODE_PICK, form, null)))
            return b.build()
        }

        @Suppress("DEPRECATION")
        private fun dataset(form: Form, p: RemoteViews, sender: IntentSender): Dataset {
            val d = Dataset.Builder(p)
            for (id in form.ids) d.setValue(id, null, p)
            return d.setAuthentication(sender).build()
        }

        fun presentation(ctx: Context, title: String, sub: String?): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.autofill_item).apply {
                setTextViewText(R.id.title, title)
                setTextViewText(R.id.subtitle, sub.orEmpty())
            }
    }
}

internal fun Intent.putForm(form: Form): Intent = apply {
    form.username?.let { putExtra("u", it) }
    form.password?.let { putExtra("p", it) }
    form.otp?.let { putExtra("o", it) }
    putExtra("domain", form.webDomain)
    putExtra("pkg", form.packageName)
}

@Suppress("DEPRECATION")
internal fun Intent.form(): Form = Form(
    getParcelableExtra<AutofillId>("u"), getParcelableExtra<AutofillId>("p"), getParcelableExtra<AutofillId>("o"),
    getStringExtra("domain"), getStringExtra("pkg").orEmpty(),
)

internal var requestCounter = 0
internal fun pending(ctx: Context, intent: Intent): IntentSender =
    PendingIntent.getActivity(ctx, ++requestCounter, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
