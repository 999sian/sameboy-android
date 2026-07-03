package io.sameboy.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.drawable.ColorDrawable
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.sameboy.android.cupertino.ActionSheetContent
import io.sameboy.android.cupertino.CupText
import io.sameboy.android.cupertino.Cupertino
import io.sameboy.android.cupertino.CupertinoTheme
import io.sameboy.android.cupertino.SheetAction
import java.io.File

/** In-game menu + save/load slot picker, Cupertino action-sheet style.
 *  The host pauses emulation before show() and unpauses in onMenuClosed(). */
object GameMenuDialog {
    const val SLOTS = 4

    interface Host {
        fun onMenuClosed()
        fun onSaveSlot(slot: Int)
        fun onLoadSlot(slot: Int)
        fun onResetGame()
        fun onSwitchModel(model: Int)
        fun onOpenSettings()
        fun onConnectAccessory(which: Int)   // 0 = None, 1 = Printer
        fun onPrinterFeed()
        fun onLinkCable()
        fun printerConnected(): Boolean
        fun onExitGame()
        fun stateFile(slot: Int): File
        fun thumbnail(slot: Int): Bitmap?
    }

    private enum class Screen { Menu, SaveSlots, LoadSlots, Models, Accessory }

    @JvmStatic
    fun show(a: Activity, h: Host) {
        val chained = booleanArrayOf(false)   // an Activity took over; don't unpause yet
        val dialog = ComponentDialog(a)
        dialog.setContentView(ComposeView(a).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setContent {
                CupertinoTheme(fillBackground = false) {
                    MenuContent(
                        h,
                        dismiss = { dialog.dismiss() },
                        takeOver = { chained[0] = true; dialog.dismiss() },
                    )
                }
            }
        })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(AColor.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { if (!chained[0]) h.onMenuClosed() }
        dialog.show()
    }

    @Composable
    private fun MenuContent(h: Host, dismiss: () -> Unit, takeOver: () -> Unit) {
        var screen by remember { mutableStateOf(Screen.Menu) }
        when (screen) {
            Screen.Menu -> ActionSheetContent(
                title = "SameBoy",
                actions = listOf(
                    SheetAction("Save state") { screen = Screen.SaveSlots },
                    SheetAction("Load state") { screen = Screen.LoadSlots },
                    SheetAction("Reset") { h.onResetGame(); dismiss() },
                    SheetAction("Model") { screen = Screen.Models },
                    SheetAction("Connect accessory") { screen = Screen.Accessory },
                    SheetAction("Printer feed") { h.onPrinterFeed(); takeOver() },
                    SheetAction("Link cable") { h.onLinkCable(); takeOver() },
                    SheetAction("Settings") { h.onOpenSettings(); takeOver() },
                    SheetAction("Exit", destructive = true) { h.onExitGame(); takeOver() },
                ),
                cancelLabel = "Resume",
                onDismiss = dismiss,
            )
            Screen.Models -> ActionSheetContent(
                title = "Model (reboots the game)",
                actions = listOf(
                    SheetAction("Game Boy (DMG)") { h.onSwitchModel(NativeBridge.MODEL_DMG_B); dismiss() },
                    SheetAction("Game Boy Color (CGB)") { h.onSwitchModel(NativeBridge.MODEL_CGB_E); dismiss() },
                    SheetAction("Game Boy Advance (AGB)") { h.onSwitchModel(NativeBridge.MODEL_AGB); dismiss() },
                ),
                cancelLabel = "Cancel",
                onDismiss = dismiss,
            )
            Screen.Accessory -> {
                val connected = h.printerConnected()
                ActionSheetContent(
                    title = stringResource(R.string.connect_accessory),
                    actions = listOf(
                        SheetAction(
                            (if (!connected) "\u2713 " else "") + stringResource(R.string.accessory_none),
                        ) { h.onConnectAccessory(0); dismiss() },
                        SheetAction(
                            (if (connected) "\u2713 " else "") + stringResource(R.string.accessory_printer),
                        ) { h.onConnectAccessory(1); dismiss() },
                    ),
                    cancelLabel = "Cancel",
                    onDismiss = dismiss,
                )
            }
            Screen.SaveSlots -> SlotSheet(h, forSave = true, dismiss = dismiss)
            Screen.LoadSlots -> SlotSheet(h, forSave = false, dismiss = dismiss)
        }
    }

    @Composable
    private fun SlotSheet(h: Host, forSave: Boolean, dismiss: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Cupertino.colors.secondarySystemGroupedBackground)
                    .padding(12.dp),
            ) {
                CupText(
                    if (forSave) "Save to slot" else "Load from slot",
                    Cupertino.type.headline,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (r in 0 until SLOTS step 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (slot in r until minOf(r + 2, SLOTS)) {
                            SlotCard(h, slot, forSave, dismiss, Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Cupertino.colors.secondarySystemGroupedBackground)
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = dismiss)
                    .height(57.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CupText("Cancel", Cupertino.type.headline, Cupertino.colors.systemBlue)
            }
        }
    }

    @Composable
    private fun SlotCard(h: Host, slot: Int, forSave: Boolean, dismiss: () -> Unit, modifier: Modifier) {
        val file = h.stateFile(slot)
        val exists = file.exists()
        val enabled = forSave || exists
        val bmp = if (exists) h.thumbnail(slot) else null
        Row(
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Cupertino.colors.fill)
                .let {
                    if (enabled) it.clickable(remember { MutableInteractionSource() }, indication = null) {
                        if (forSave) h.onSaveSlot(slot) else h.onLoadSlot(slot)
                        dismiss()
                    } else it
                }
                .alpha(if (enabled) 1f else 0.4f)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(64.dp, 58.dp).clip(RoundedCornerShape(6.dp)).background(Cupertino.colors.separator)) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp, 58.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column {
                CupText("Slot ${slot + 1}", Cupertino.type.body, maxLines = 1)
                CupText(
                    if (exists) DateUtils.getRelativeTimeSpanString(file.lastModified()).toString() else "Empty",
                    Cupertino.type.footnote, Cupertino.colors.secondaryLabel, maxLines = 1,
                )
            }
        }
    }
}
