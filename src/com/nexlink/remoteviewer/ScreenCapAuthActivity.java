package com.nexlink.remoteviewer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

public class ScreenCapAuthActivity extends Activity {
	@Override
	protected void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
	    startActivityForResult(((MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE)).createScreenCaptureIntent(), 0);
	}
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data){
		CaptureService cs = CaptureService.getInstance();
		if(cs != null){
		    cs.onActivityResultWrapper(requestCode, resultCode, data);
		}
		finish();
	}
}
