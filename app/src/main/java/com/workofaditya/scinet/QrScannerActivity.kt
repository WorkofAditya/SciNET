package com.workofaditya.scinet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class QrScannerActivity : ComponentActivity() {
    private val scannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents.isNullOrBlank()) {
            setResult(Activity.RESULT_CANCELED)
        } else {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra("scinet_host", result.contents.trim())
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan a SciNET QR code from a PC or another device")
            setBeepEnabled(true)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
        scannerLauncher.launch(options)
    }
}
