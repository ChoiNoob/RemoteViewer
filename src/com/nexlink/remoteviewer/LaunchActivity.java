package com.nexlink.remoteviewer;

import com.nexlink.remoteviewer.R;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class LaunchActivity extends Activity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_boot);
		super.onCreate(savedInstanceState);
		startService(new Intent(this, CaptureService.class));
		finish();
	}
}
