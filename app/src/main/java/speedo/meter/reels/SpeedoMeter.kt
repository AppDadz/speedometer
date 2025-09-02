package speedo.meter.reels

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import speedo.meter.reels.databinding.FragmentFirstBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SpeedoMeter : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private lateinit var locationCallback: LocationCallback
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var manager: ReviewManager
    private val PREFS_NAME = "AppPrefs"
    private val KEY_APP_OPEN_COUNT = "appOpenCount"

    override fun onAttach(context: Context) {
        super.onAttach(context)
        manager = ReviewManagerFactory.create(context)
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndConnectivity()
    }

    private fun checkPermissionsAndConnectivity() {
        if (!isNetworkConnected()) {
            showSnackbar("Internet connection is required") {
                checkPermissionsAndConnectivity()
            }
        } else if (!isLocationEnabled()) {
            showSnackbar("GPS is turned off. Please enable it and try again.") {
                checkPermissionsAndConnectivity()
            }
        } else {
            startLocationUpdates()
        }
    }
    private fun isNetworkConnected(): Boolean {
        try {
            val context = context ?: return false
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

            // For API 23+ (Marshmallow and above), use NetworkCapabilities
            val network = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            myLog(TAG, "Error checking network connection: ${e.message}")
            return false
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun showSnackbar(message: String, action: () -> Unit) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        myLog(TAG, "onCreateView: Fragment view created")

        val sharedPreferences: SharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appOpenCount = sharedPreferences.getInt(KEY_APP_OPEN_COUNT, 0)
        myLog("viewwwwww", "open count -> $appOpenCount")
        if (appOpenCount >= 1) {
            getReview()
            myLog("viewwwwww", "show review")
        }

        binding.resetButton.setOnClickListener {
            checkPermissionsAndConnectivity()
        }

        with(sharedPreferences.edit()) {
            putInt(KEY_APP_OPEN_COUNT, appOpenCount + 1)
            apply()
        }

        return binding.root
    }

    private fun getReview() {
        try {
            val request = manager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    val flow = manager.launchReviewFlow(requireActivity(), reviewInfo)
                    flow.addOnCompleteListener {
                        myLog("viewwwwww", "success ${it.result}")
                    }
                    flow.addOnFailureListener {
                        myLog("viewwwwww", "fail ${it.message}")
                    }
                } else {
                    val reviewErrorCode = (task.exception as ReviewException).errorCode
                    myLog("viewwwwww", "failed -> $reviewErrorCode")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestLocationPermissions() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            myLog(TAG, "Location permission requested")
        } else {
            myLog(TAG, "Permissions already granted")
            startLocationUpdates()
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                myLog(TAG, "Permissions granted")
                startLocationUpdates()
            } else {
                myLog(TAG, "Permissions denied")
                showSnackbar("Location permission denied") {
                    checkPermissionsAndConnectivity()
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        myLog(TAG, "onViewCreated: Fragment view created")

        fixStatusBar()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (locationResult.locations.isNotEmpty()) {
                    handleLocationResult(locationResult)
                }
            }
        }

        requestLocationPermissions()
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun handleLocationResult(locationResult: LocationResult) {
        try {
            for (location in locationResult.locations) {
                val speedInMetersPerSecond = location.speed
                val speedKmph = speedInMetersPerSecond * 3.6
                val maxSpeed = 240f
                val maxRotation = 240f

                val speedMaxCalc = speedKmph.coerceAtMost(maxSpeed.toDouble())
                val rotationAngle = ((speedMaxCalc / maxSpeed) * maxRotation).toFloat()

                myLog(TAG, "Speed: $speedKmph km/h")

                animateSpeedometer(rotationAngle, speedMaxCalc, speedKmph)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun animateSpeedometer(rotationAngle: Float, speedMaxCalc: Double, speedKmph: Double) {
        ObjectAnimator.ofFloat(
            binding.speedometerLine,
            "rotation",
            binding.speedometerLine.rotation,
            rotationAngle
        ).apply {
            duration = 300
            interpolator = LinearInterpolator()
            start()
        }

        if (speedMaxCalc > 80) {
            ObjectAnimator.ofFloat(
                binding.meterTop,
                "rotation",
                binding.meterTop.rotation,
                rotationAngle - 100f
            ).apply {
                duration = 300
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            ObjectAnimator.ofFloat(
                binding.meterTop,
                "rotation",
                binding.meterTop.rotation,
                0f
            ).apply {
                duration = 300
                interpolator = LinearInterpolator()
                start()
            }
        }

        if (speedMaxCalc > 145) {
            ObjectAnimator.ofFloat(
                binding.meterRight,
                "rotation",
                binding.meterRight.rotation,
                rotationAngle - 170f
            ).apply {
                duration = 300
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            ObjectAnimator.ofFloat(
                binding.meterRight,
                "rotation",
                binding.meterRight.rotation,
                0f
            ).apply {
                duration = 300
                interpolator = LinearInterpolator()
                start()
            }
        }

        val currentSpeed = binding.speed.text.toString().toIntOrNull() ?: 0
        ValueAnimator.ofInt(currentSpeed, speedKmph.roundToInt()).apply {
            duration = 300
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                binding.speed.text = animator.animatedValue.toString()
            }
            start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        _binding = null
        myLog(TAG, "onDestroyView: View destroyed and location updates removed")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun fixStatusBar() {
        val statusBarHeight = getStatusBarHeight(requireContext())
        val statusBarView = binding.statusBar
        val layoutParams = statusBarView.layoutParams
        layoutParams.height = statusBarHeight
        statusBarView.layoutParams = layoutParams
    }

    private fun getStatusBarHeight(context: Context): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (LocationManagerCompat.isLocationEnabled(lm)) {
                val locationRequest =
                    LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500)
                        .setGranularity(Granularity.GRANULARITY_FINE)
                        .build()

                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
                myLog(TAG, "Location updates started")
            } else {
                myLog(TAG, "GPS is turned off")
                showSnackbar("GPS is turned off. Please enable it and try again.") {
                    checkPermissionsAndConnectivity()
                }
            }
        } else {
            myLog(TAG, "Location permission is off")
        }
    }

    private fun myLog(tag: String, msg: String) {
        // Log.d(tag, msg)
    }

    companion object {
        private const val TAG = "SpeedoMeter"
    }
}