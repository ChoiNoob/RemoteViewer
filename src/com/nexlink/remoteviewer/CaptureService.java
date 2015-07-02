package com.nexlink.remoteviewer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraDevice.StateCallback;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;
 
public class CaptureService extends Service {
	
	private static CaptureService mInstance;
	
    private static final int PORT = 8080;
	
	private AssetManager mAssetManager;
    private MediaProjectionManager mMediaProjectionManager;
    private CameraManager mCameraManager;
    private MyHTTPD mServer;
    private ImageReader mImageReader;
    private Image mImage;
    private Handler mHandler = new Handler();
    private Handler mTimeoutHandler = new Handler();
  
    private Source screenSource;
    private Source frontCameraSource;
    private Source backCameraSource;
    private Source currentSource;
    private Runnable mTimeout = new Runnable(){
		@Override
		public void run() {
			stopCapture();
			currentSource = null;
		}
    };
    
    private VirtualDisplay mVirtualDisplay;

    private boolean mImageReaderPending = false;
    private Semaphore mCameraMutex = new Semaphore(1);
  
    private CameraCaptureSession openCaptureSession;
  
    private class Source{
	    String id;
	    int width;
	    int height;
	    public Source(String id, int width, int height){
		    this.id = id;
	  	    this.width = width;
		    this.height = height;
	    }
  }
  
  private boolean isNetworkAvailable() {
     ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
     NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
     return (activeNetworkInfo != null && activeNetworkInfo.isConnected()/* && activeNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI*/);
    }
    
  public static String getStringFromInputStream(InputStream stream, String charsetName) throws IOException
  {
      int n = 0;
      final char[] buffer = new char[1024 * 4];
      final InputStreamReader reader = new InputStreamReader(stream, charsetName);
      final StringWriter writer = new StringWriter();
      while (-1 != (n = reader.read(buffer))) writer.write(buffer, 0, n);
      return writer.toString();
  }
  
  private void stopCapture(){
	  if(mVirtualDisplay != null){
		  mVirtualDisplay.setSurface(null);
	  }  
	  if(openCaptureSession != null){
		  openCaptureSession.close();
		  mCameraMutex.acquireUninterruptibly(); //Wait for current camera and capture session to close
		  openCaptureSession = null;
	  }
	  
	  if(mImageReader != null){
		  mImageReader.close();
	  }
  }
  
  private void startCapture(Source source){
	  mImageReaderPending = true;
	  stopCapture();
	  //Create new ImageReader
	  mImageReader = ImageReader.newInstance(source.width, source.height, source == screenSource ? PixelFormat.RGBA_8888 : ImageFormat.JPEG, 3);
	  mImageReader.setOnImageAvailableListener(new OnImageAvailableListener(){
			@Override
			public void onImageAvailable(ImageReader arg0) {
				final Image img = arg0.acquireLatestImage();
				if(img != null){
					if(mImage != null){
						mImage.close();
					}
					mImage = img;
					mImageReaderPending = false;
				}
			}
		}, mHandler);
	  
	  if(source == frontCameraSource || source == backCameraSource){
		  
	  try {
		mCameraManager.openCamera(source.id, new StateCallback(){
				@Override
				public void onOpened(CameraDevice camera) {
					try {
						camera.createCaptureSession(Arrays.asList(mImageReader.getSurface()), new CameraCaptureSession.StateCallback(){
							@Override
							public void onClosed(CameraCaptureSession captureSession){
								captureSession.getDevice().close();
							}
							@Override
							public void onConfigureFailed(CameraCaptureSession captureSession) {
								captureSession.close();
							}
							@Override
							public void onConfigured(CameraCaptureSession captureSession) {
								try {
									CaptureRequest.Builder requestBuilder = captureSession.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
									requestBuilder.addTarget(mImageReader.getSurface());
									requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO); 
									requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, 0);
									captureSession.setRepeatingRequest(requestBuilder.build(), null, mHandler);
									openCaptureSession = captureSession;
								} catch (CameraAccessException e) {
									captureSession.close();
									e.printStackTrace();
								}
							}
						}, mHandler);
					} catch (CameraAccessException e) {
						camera.close();
						e.printStackTrace();
					}
				}
				@Override
				public void onClosed(CameraDevice arg0){
					mCameraMutex.release();
					}
				@Override
				public void onDisconnected(CameraDevice camera) {
					camera.close();
					}
				@Override
				public void onError(CameraDevice camera, int error) {
					camera.close();
					}
			
		}, mHandler);
	    } catch (CameraAccessException e) {
		    mCameraMutex.release();
		    e.printStackTrace();
	      }
	  }
	  else if(mVirtualDisplay != null){
			mVirtualDisplay.setSurface(mImageReader.getSurface());
	  }
	  currentSource = source;
  }
  
  @Override
  public void onCreate() {
    super.onCreate();
    mInstance = this;
    mAssetManager = getAssets();
    mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    try {
		for(final String cameraId : mCameraManager.getCameraIdList()){
		    final CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
		    final int cOrientation = characteristics.get(CameraCharacteristics.LENS_FACING);
		    final Size[] jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
		    if (jpegSizes != null && jpegSizes.length > 0) { //Just grab the smallest size jpg available
                final int width = jpegSizes[0].getWidth();  
                final int height = jpegSizes[0].getHeight();
                if(cOrientation == CameraCharacteristics.LENS_FACING_FRONT){
    		    	frontCameraSource = new Source(cameraId, width, height);
    		    }
    		    else if(cOrientation == CameraCharacteristics.LENS_FACING_BACK){
    		    	backCameraSource = new Source(cameraId, width, height);
    		    }
            }  
		}
	} catch (CameraAccessException e) {
		e.printStackTrace();
	}
    mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    startActivity(new Intent(this, ScreenCapAuthActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }
  
  @Override
public void onDestroy(){
	  mInstance = null;
  }
  
	protected void onActivityResultWrapper(int requestCode, int resultCode, Intent data){
	    final MediaProjection mediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
	    if(mediaProjection != null){
	        final Display realDisplay = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
		    final DisplayMetrics metrics = new DisplayMetrics();
		    realDisplay.getRealMetrics(metrics);
	        screenSource = new Source(realDisplay.getName(), metrics.widthPixels/2, metrics.heightPixels/2);
		    mVirtualDisplay = mediaProjection.createVirtualDisplay(screenSource.id, metrics.widthPixels/2, metrics.heightPixels/2, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null);
	    }
	    WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
	    int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
	    final String formatedIpAddress = String.format(Locale.ENGLISH, "%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
	    Toast.makeText(this, "Please access: http://" + formatedIpAddress + ":" + PORT, Toast.LENGTH_LONG).show();
	    try {
	        mServer = new MyHTTPD();
	        mServer.closeAllConnections();
	        mServer.start();
	    } catch (IOException e) {
	      e.printStackTrace();
	    }
	}

  private class MyHTTPD extends NanoHTTPD {
    public MyHTTPD() throws IOException {
      super(PORT);
    }
 
    @Override public Response serve(IHTTPSession session) {
    	mTimeoutHandler.removeCallbacks(mTimeout);
    	mTimeoutHandler.postDelayed(mTimeout, 60000);
        Map<String, String> params = session.getParms();
        String source = params.get("source");
        Response response = new Response(Response.Status.NO_CONTENT, "", "");
        if(source != null){
        	try{
        	    if(mImageReaderPending){
        	   // 	return new Response(Response.Status.OK,"image/jpg", mAssetManager.open("no_image.jpg"));
        	    }
        	    final Image.Plane[] planes = mImage.getPlanes();
                final ByteBuffer buffer = planes[0].getBuffer();
                buffer.rewind();
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                //Need to encode screencast
                if(mImage.getFormat() != ImageFormat.JPEG){
            	    final Bitmap bitmap = Bitmap.createBitmap(mImage.getWidth(), mImage.getHeight(), Bitmap.Config.ARGB_8888);
            	    bitmap.copyPixelsFromBuffer(buffer);
            	    bitmap.compress(CompressFormat.JPEG, 50, bos);
                }
                //Camera returns jpgs natively
                else{
            	    final byte[] bytes = new byte[buffer.capacity()];
            	    buffer.get(bytes);
				    bos.write(bytes);
                }
                response = new Response(Response.Status.OK,"image/jpg", new ByteArrayInputStream(bos.toByteArray()));
                response.addHeader("Content-Length", String.valueOf(bos.size()));
        	}
        	catch(Exception e){
        		try {
					response = new Response(Response.Status.OK,"image/jpg", mAssetManager.open("no_image.jpg"));
				} catch (IOException e1) {}
        	}

        	if("screen".compareTo(source) == 0 && currentSource != screenSource && screenSource != null){
        		startCapture(screenSource);
            }
            else if("frontcam".compareTo(source) == 0 && currentSource != frontCameraSource && frontCameraSource != null){
            	startCapture(frontCameraSource);
            }
            else if("backcam".compareTo(source) == 0 && currentSource != backCameraSource && backCameraSource != null){
            	startCapture(backCameraSource);
            }
        }
        else{
			try{
			    final String html = getStringFromInputStream(mAssetManager.open("index.html"), "UTF-8");
			    response = new Response(Response.Status.OK,"text/html", html);
			   } catch (IOException e) {
				e.printStackTrace();
			} 
        }
        return response;
    }
  }
  
  public static CaptureService getInstance(){
	  return mInstance;
  }

@Override
public IBinder onBind(Intent arg0) {
	// TODO Auto-generated method stub
	return null;
}
}
