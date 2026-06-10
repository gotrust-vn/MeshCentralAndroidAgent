package com.meshcentral.agent

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.budiyev.android.codescanner.CodeScanner
import com.budiyev.android.codescanner.CodeScannerView
import com.budiyev.android.codescanner.DecodeCallback

/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class ScannerFragment : Fragment() {
    private var lastToast : Toast? = null
    private var codeScanner: CodeScanner? = null
    private var scannerView: CodeScannerView? = null
    var alert : AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.scanner_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scannerFragment = this
        visibleScreen = 2

        view.findViewById<Button>(R.id.button_second).setOnClickListener {
            lastToast?.cancel()
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }

        scannerView = view.findViewById(R.id.scanner_view)
        lastToast = Toast.makeText(requireActivity(), "", Toast.LENGTH_LONG)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            initCodeScanner()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initCodeScanner()
            } else {
                findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
            }
        }
    }

    // CodeScanner spawns a background thread (CodeScannerSInitializationThread) that can throw
    // CodeScannerException asynchronously — a normal try-catch on the constructor won't catch it.
    // We install a temporary uncaught exception handler to intercept that and navigate back
    // gracefully instead of crashing.
    private fun initCodeScanner() {
        if (codeScanner != null) {
            codeScanner?.startPreview()
            return
        }
        val sv = scannerView ?: return
        val act = activity ?: return

        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            Thread.setDefaultUncaughtExceptionHandler(prevHandler)
            val isCameraError = ex.javaClass.name.contains("CodeScanner", ignoreCase = true) ||
                ex.message?.contains("camera", ignoreCase = true) == true
            if (isCameraError) {
                act.runOnUiThread {
                    codeScanner = null
                    cameraPresent = false
                    try {
                        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
                        (act as? MainActivity)?.promptForServerLink()
                    } catch (_: Exception) {}
                }
            } else {
                try {
                    val trace = android.util.Log.getStackTraceString(ex)
                    val prefs = act.getSharedPreferences("meshagent", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putString("last_crash", "[${thread.name}] $ex\n$trace").apply()
                } catch (_: Exception) {}
                prevHandler?.uncaughtException(thread, ex)
            }
        }

        try {
            codeScanner = CodeScanner(act, sv)
            codeScanner!!.decodeCallback = DecodeCallback {
                act.runOnUiThread {
                    if (isMshStringValid(it.text)) {
                        lastToast?.cancel()
                        confirmServerSetup(it.text)
                    } else {
                        lastToast?.setGravity(Gravity.CENTER, 0, 300)
                        lastToast?.setText(getString(R.string.invalid_qrcode))
                        lastToast?.show()
                        codeScanner?.startPreview()
                    }
                }
            }
            sv.setOnClickListener { codeScanner?.startPreview() }
            codeScanner?.startPreview()
        } catch (e: Exception) {
            Thread.setDefaultUncaughtExceptionHandler(prevHandler)
            codeScanner = null
            cameraPresent = false
            try {
                findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
                (act as? MainActivity)?.promptForServerLink()
            } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        codeScanner?.releaseResources()
        super.onPause()
    }

    override fun onDestroy() {
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        lastToast?.cancel()
        super.onDestroy()
    }

    fun getServerHost(serverLink : String?) : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink.split(',')
        var serverHost = x[0]
        return serverHost.substring(5)
    }

    fun confirmServerSetup(x:String) {
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("MeshCentral Server")
        builder.setMessage(getString(R.string.setup_message, getServerHost(x)))
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            visibleScreen = 1
            (activity as MainActivity).setMeshServerLink(x)
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }
        builder.setNeutralButton(android.R.string.cancel) { _, _ ->
            codeScanner?.startPreview()
        }
        alert = builder.show()
    }

    fun exit() {
        findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 200
    }

    fun isMshStringValid(x:String):Boolean {
        if (x.startsWith("mc://") == false)  return false
        var xs = x.split(',')
        if (xs.count() < 3) return false
        if (xs[0].length < 8) return false
        if (xs[1].length < 3) return false
        if (xs[2].length < 3) return false
        if (xs[0].indexOf('.') == -1) return false
        return true
    }
}
