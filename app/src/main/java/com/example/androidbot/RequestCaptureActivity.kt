package com.example.androidbot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * نافذة شفافة (ما بتظهر أي واجهة فعلية) - بس بتطلب صلاحية التقاط الشاشة
 * فورًا لحظة ما تفتح، وبعدها تسكر نفسها تلقائيًا.
 * الهدف: نقدر نعيد تفعيل الصلاحية بضغطة وحدة من الزر العائم، بدون ما نرجع
 * للتطبيق الرئيسي.
 */
class RequestCaptureActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 4242
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                }
                startForegroundService(serviceIntent)
                Toast.makeText(this, "تم تفعيل التقاط الشاشة من جديد ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "تم رفض صلاحية التقاط الشاشة", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
