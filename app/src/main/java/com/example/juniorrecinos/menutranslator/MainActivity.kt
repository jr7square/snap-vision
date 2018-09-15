package com.example.juniorrecinos.menutranslator

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.content.Intent
import kotlinx.android.synthetic.main.activity_main.*
import android.graphics.Bitmap
import android.app.Activity
import android.util.Log

import java.io.File
import android.os.AsyncTask
import android.widget.TextView
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.vision.v1.Vision
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.*
import com.google.api.services.vision.v1.VisionRequest
import com.google.api.services.vision.v1.VisionRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.http.HttpTransport
import com.google.api.services.vision.v1.model.*
import java.io.ByteArrayOutputStream


class MainActivity : AppCompatActivity() {

    val requestImageCapture = 1
    val max_dimension = 1200
    val CLOUD_VISION_API_KEY = "AIzaSyD9lvoSUOqELgKIiiDwhD6Vo0T19JsOqOk"
    val ANDROID_CERT_HEADER = "X-Android-Cert"
    val ANDROID_PACKAGE_HEADER = "X-Android-Package"
    val MAX_LABEL_RESULTS = 10



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        button.setOnClickListener { _ ->
            dispatchTakePictureIntent()
        }


    }

    private fun dispatchTakePictureIntent() {
        val requestImageCapture = 1
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)

        if (takePictureIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(takePictureIntent, requestImageCapture)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(requestCode == requestImageCapture && resultCode == Activity.RESULT_OK) {
            val extras = data?.extras
            val imageBitmap = extras?.get("data") as Bitmap
            val scaledBitmap = scaleBitmapDown(imageBitmap, max_dimension)
            callCloudVision(scaledBitmap)

        }
    }


    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        var resizedWidth = maxDimension
        var resizedHeight = maxDimension

        if (originalHeight > originalWidth) {
            resizedHeight = maxDimension
            resizedWidth = (resizedHeight * originalWidth.toFloat() / originalHeight.toFloat()).toInt()
        } else if (originalWidth > originalHeight) {
            resizedWidth = maxDimension
            resizedHeight = (resizedWidth * originalHeight.toFloat() / originalWidth.toFloat()).toInt()
        } else if (originalHeight == originalWidth) {
            resizedHeight = maxDimension
            resizedWidth = maxDimension
        }
        return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, false)
    }

    private fun callCloudVision(bitmap: Bitmap) {
        // Switch text to loading
       // mImageDetails.setText(R.string.loading_message)

        // Do the real work in an async task, because we need to use the network anyway
        try {
            val labelDetectionTask = LableDetectionTask(this, prepareAnnotationRequest(bitmap))
            labelDetectionTask.execute()
        } catch (e: IOException) {
            Log.d("boy", "failed to make API request because of other IOException " + e.message)
        }

    }

    @Throws(IOException::class)
    private fun prepareAnnotationRequest(bitmap: Bitmap): Vision.Images.Annotate {
        val httpTransport = AndroidHttp.newCompatibleTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()

        val requestInitializer = object : VisionRequestInitializer(CLOUD_VISION_API_KEY) {
            /**
             * We override this so we can inject important identifying fields into the HTTP
             * headers. This enables use of a restricted cloud platform API key.
             */
            @Throws(IOException::class)
            override fun initializeVisionRequest(visionRequest: VisionRequest<*>?) {
                super.initializeVisionRequest(visionRequest)

                val packageName = packageName
                visionRequest!!.requestHeaders.set(ANDROID_PACKAGE_HEADER, packageName)

                val sig = PackageManagerUtils.getSignature(packageManager, packageName)

                visionRequest.requestHeaders.set(ANDROID_CERT_HEADER, sig)
            }
        }

        val builder = Vision.Builder(httpTransport, jsonFactory, null)
        builder.setVisionRequestInitializer(requestInitializer)

        val vision = builder.build()

        val batchAnnotateImagesRequest = BatchAnnotateImagesRequest()
        batchAnnotateImagesRequest.requests = object : ArrayList<AnnotateImageRequest>() {
            init {
                val annotateImageRequest = AnnotateImageRequest()

                // Add the image
                val base64EncodedImage = Image()
                // Convert the bitmap to a JPEG
                // Just in case it's a format that Android understands but Cloud Vision
                val byteArrayOutputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
                val imageBytes = byteArrayOutputStream.toByteArray()

                // Base64 encode the JPEG
                base64EncodedImage.encodeContent(imageBytes)
                annotateImageRequest.image = base64EncodedImage

                // add the features we want
                annotateImageRequest.features = object : ArrayList<Feature>() {
                    init {
                        val labelDetection = Feature()
                        labelDetection.setType("LABEL_DETECTION")
                        labelDetection.setMaxResults(MAX_LABEL_RESULTS)
                        add(labelDetection)
                    }
                }

                // Add the list of one thing to the request
                add(annotateImageRequest)
            }
        }

        val annotateRequest = vision.images().annotate(batchAnnotateImagesRequest)
        // Due to a bug: requests to Vision API containing large images fail when GZipped.
        annotateRequest.disableGZipContent = true
        Log.d("boy", "created Cloud Vision request object, sending request")

        return annotateRequest
    }

    private class LableDetectionTask internal constructor(activity: MainActivity, private val mRequest: Vision.Images.Annotate) : AsyncTask<Any, Void, String>() {
        private val mActivityWeakReference: WeakReference<MainActivity>

        init {
            mActivityWeakReference = WeakReference(activity)
        }

        override fun doInBackground(vararg params: Any): String {
            try {
                Log.d("boy", "created Cloud Vision request object, sending request")
                val response = mRequest.execute()
                return convertResponseToString(response)

            } catch (e: GoogleJsonResponseException) {
                Log.d("boy", "failed to make API request because " + e.content)
            } catch (e: IOException) {
                Log.d("boy", "failed to make API request because of other IOException " + e.message)
            }

            return "Cloud Vision API request failed. Check logs for details."
        }

        override fun onPostExecute(result: String) {
            val activity = mActivityWeakReference.get()
            if (!activity!!.isFinishing) {
                val imageDetail = activity.image_details
                imageDetail?.text = result
            }
        }

        private fun convertResponseToString(response: BatchAnnotateImagesResponse): String {
            val message = StringBuilder("I found these things:\n\n")

            val labels = response.responses[0].labelAnnotations
            if (labels != null) {
                for (label in labels) {
                    message.append(String.format(Locale.US, "%.3f: %s", label.score, label.description))
                    message.append("\n")
                }
            } else {
                message.append("nothing")
            }

            return message.toString()
        }
    }




//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        if (requestCode == requestImageCapture && resultCode == Activity.RESULT_OK) {
//            val extras = data?.extras
//            val imageBitmap = extras?.get("data") as Bitmap
//
//            captured_image.setImageBitmap(imageBitmap)
//
//            "/translate-image".httpUpload(parameters = listOf("image" to "testImage")).dataParts{ request, url ->
//                listOf(
//                        DataPart(File.createTempFile("temp1", ".tmp"), "image/jpeg")
//                )
//            }.responseJson { request, response, result ->
//                Log.d("boy", response.data.toString())
//            }
//
//
//        }
//    }

//    private fun testBackend() {
//        "/test".httpGet().responseString { request, response, result ->
//
//            Log.d("Boy", request.cUrlString())
//            Log.d("Boy", request.toString())
//            Log.d("Boy", response.responseMessage)
//            Log.d("Boy", response.data.toString())
//        }
//    }



}
