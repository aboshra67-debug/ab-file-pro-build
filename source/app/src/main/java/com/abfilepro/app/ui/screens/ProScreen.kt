package com.abfilepro.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abfilepro.app.ads.AdsHost
import com.abfilepro.app.billing.BillingBackend
import com.abfilepro.app.billing.ProOffer
import com.abfilepro.app.billing.ProProducts
import com.abfilepro.app.billing.ProState
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.screens.assistant.tr

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** P28: what Pro gives, the plans as Google Play prices them, restore, manage, and (test builds only) a local unlock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val pro = ProState.pro

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AB Pro", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, tr(language, "رجوع", "Back", "Retour")) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.WorkspacePremium, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(
                            if (pro) tr(language, "أنت مشترك في Pro", "You have Pro", "Vous avez Pro")
                            else tr(language, "ترقّى إلى Pro", "Upgrade to Pro", "Passez à Pro"),
                            fontWeight = FontWeight.Black, fontSize = 18.sp,
                        )
                        Text(
                            tr(language, "كل مميزات المساعد الشخصي بلا حدود وبدون إعلانات", "Every assistant feature without limits, and no ads", "Toutes les fonctions de l'assistant sans limites, sans publicité"),
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(language, "مع Pro", "With Pro", "Avec Pro"), fontWeight = FontWeight.Black)
                    listOf(
                        tr(language, "مهام ومواعيد متكررة بلا حد (المجاني: 3)", "Unlimited repeating tasks and appointments (free: 3)", "Tâches et rendez-vous récurrents illimités (gratuit : 3)"),
                        tr(language, "أفراد عائلة بلا حد في الأدوية (المجاني: 2)", "Unlimited family members for medicines (free: 2)", "Membres de la famille illimités pour les médicaments (gratuit : 2)"),
                        tr(language, "حتى 4 جرعات يوميًا للدواء (المجاني: 2)", "Up to 4 doses a day per medicine (free: 2)", "Jusqu'à 4 doses par jour (gratuit : 2)"),
                        tr(language, "مصروفات شهرية ثابتة بلا حد (المجاني: 2)", "Unlimited fixed monthly expenses (free: 2)", "Dépenses mensuelles fixes illimitées (gratuit : 2)"),
                        tr(language, "بدون إعلانات", "No ads", "Sans publicité"),
                    ).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Text(line, fontSize = 14.sp)
                        }
                    }
                    Text(
                        tr(language, "كل ما كان يعمل قبل كده يفضل يعمل مجانًا.", "Everything that worked before stays free.", "Tout ce qui fonctionnait avant reste gratuit."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
                    )
                }
            }

            if (!BillingBackend.available) {
                Text(
                    tr(language, "هذه النسخة (التثبيت المباشر) مفتوحة بالكامل ولا تحتاج اشتراك.", "This direct-install build is fully unlocked; no subscription is needed.", "Cette version installée directement est entièrement débloquée."),
                    fontSize = 13.sp,
                )
            } else {
                val offers = BillingBackend.offers
                if (offers.isEmpty()) {
                    Text(
                        tr(language, "جارٍ تحميل الباقات من Google Play… لو فضلت فاضية فالمنتجات لسه غير مُفعّلة في هذا الإصدار.", "Loading plans from Google Play… if this stays empty, the products are not active for this version yet.", "Chargement des offres Google Play…"),
                        fontSize = 13.sp,
                    )
                }
                offers.forEach { offer -> OfferCard(offer, pro) { context.findActivity()?.let { activity -> BillingBackend.launch(activity, offer.productId) } } }
                if (BillingBackend.message.isNotBlank()) {
                    Text(
                        tr(language, "تعذر الاتصال بـ Google Play. جرّب مرة أخرى بعد قليل.", "Could not reach Google Play. Try again shortly.", "Impossible de joindre Google Play."),
                        color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    )
                }
                OutlinedButton(onClick = { BillingBackend.restore() }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr(language, "استرجاع مشترياتي", "Restore purchases", "Restaurer mes achats"))
                }
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions?package=${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr(language, "إدارة الاشتراك أو إلغاؤه", "Manage or cancel subscription", "Gérer ou résilier l'abonnement")) }
                Text(
                    tr(
                        language,
                        "الاشتراك يتجدد تلقائيًا ويمكن إلغاؤه في أي وقت من Google Play. بيانات الدفع يتعامل معها Google فقط.",
                        "Subscriptions renew automatically and can be cancelled any time in Google Play. Only Google handles your payment details.",
                        "Les abonnements se renouvellent automatiquement et peuvent être résiliés à tout moment dans Google Play.",
                    ),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
                )
            }

            if (AdsHost.privacyOptionsRequired(context)) {
                TextButton(onClick = { context.findActivity()?.let { AdsHost.showPrivacyOptions(it) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr(language, "خيارات خصوصية الإعلانات", "Ad privacy options", "Options de confidentialité des annonces"))
                }
            }

            if (ProState.gatingActive && ProState.trialUnlockAvailable) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr(language, "فتح Pro للتجربة", "Trial unlock", "Déblocage d'essai"), fontWeight = FontWeight.Bold)
                            Text(
                                tr(language, "يظهر في نسخ التجربة فقط، ولا يوجد في النسخة النهائية.", "Shown in test builds only, never in the release.", "Visible uniquement dans les versions d'essai."),
                                fontSize = 12.sp,
                            )
                        }
                        Switch(checked = ProState.trialUnlock, onCheckedChange = { ProState.setTrialUnlock(context, it) })
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun OfferCard(offer: ProOffer, pro: Boolean, onBuy: () -> Unit) {
    val language = LocalAppLanguage.current
    val title = when (offer.productId) {
        ProProducts.YEARLY -> tr(language, "Pro سنوي", "Pro yearly", "Pro annuel")
        ProProducts.MONTHLY -> tr(language, "Pro شهري", "Pro monthly", "Pro mensuel")
        ProProducts.LIFETIME -> tr(language, "Pro مدى الحياة (دفعة واحدة)", "Pro lifetime (one payment)", "Pro à vie (paiement unique)")
        else -> tr(language, "إزالة الإعلانات فقط", "Remove ads only", "Supprimer les annonces")
    }
    Card(shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Black)
                Text(offer.price, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Button(onClick = onBuy, enabled = !pro || offer.productId == ProProducts.REMOVE_ADS && !ProState.adsRemoved) {
                Text(tr(language, "اشترك", "Buy", "Acheter"))
            }
        }
    }
}
