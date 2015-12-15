/*
 * Copyright (C) 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.android;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.camera.CameraManager;
import com.google.zxing.client.android.clipboard.ClipboardInterface;
import com.google.zxing.client.android.history.HistoryActivity;
import com.google.zxing.client.android.history.HistoryItem;
import com.google.zxing.client.android.history.HistoryManager;
import com.google.zxing.client.android.result.ResultButtonListener;
import com.google.zxing.client.android.result.ResultHandler;
import com.google.zxing.client.android.result.ResultHandlerFactory;
import com.google.zxing.client.android.result.supplement.SupplementalInfoRetriever;
import com.google.zxing.client.android.share.ShareActivity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.Map;

/**
 * This activity opens the camera and does the actual scanning on a background
 * thread. It draws a viewfinder to help the user place the barcode correctly,
 * shows feedback as the image processing is happening, and then overlays the
 * results when a scan is successful.
 * 
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public final class CaptureActivity extends Activity implements
		SurfaceHolder.Callback {

	private static final String TAG = CaptureActivity.class.getSimpleName();

	private static final long DEFAULT_INTENT_RESULT_DURATION_MS = 1500L;
	private static final long BULK_MODE_SCAN_DELAY_MS = 1000L;

	private static final String[] ZXING_URLS = {
			"http://zxing.appspot.com/scan", "zxing://scan/" };

	public static final int HISTORY_REQUEST_CODE = 0x0000bacc;

	private static final Collection<ResultMetadataType> DISPLAYABLE_METADATA_TYPES = EnumSet
			.of(ResultMetadataType.ISSUE_NUMBER,
					ResultMetadataType.SUGGESTED_PRICE,
					ResultMetadataType.ERROR_CORRECTION_LEVEL,
					ResultMetadataType.POSSIBLE_COUNTRY);
	/**
	 * 相机管理器
	 */
	private CameraManager cameraManager;
	/**
	 * 扫描页的 handler
	 */
	private CaptureActivityHandler handler;
	/**
	 * 保存或显示的编码结果
	 */
	private Result savedResultToShow;
	/**
	 * 扫描框
	 */
	private ViewfinderView viewfinderView;
	// 取景框提示
	private TextView statusView;
	/**
	 * 结果页
	 */
	private View resultView;
	/**
	 * 最后的扫描结果
	 */
	private Result lastResult;
	private boolean hasSurface;
	/**
	 * 是否启用粘贴板
	 */
	private boolean copyToClipboard;
	/**
	 * Intent 源
	 */
	private IntentSource source;
	/**
	 * 源数据
	 */
	private String sourceUrl;
	private ScanFromWebPageManager scanFromWebPageManager;
	/**
	 * 二维码编码集
	 */
	private Collection<BarcodeFormat> decodeFormats;
	private Map<DecodeHintType, ?> decodeHints;
	/**
	 * 字符编码集
	 */
	private String characterSet;
	/**
	 * 历史记录
	 */
	private HistoryManager historyManager;
	/**
	 * 电量监控 ，电量低时关闭
	 */
	private InactivityTimer inactivityTimer;
	// 蜂鸣器管理
	private BeepManager beepManager;
	/**
	 * 光源管理
	 */
	private AmbientLightManager ambientLightManager;

	ViewfinderView getViewfinderView() {
		return viewfinderView;
	}

	public Handler getHandler() {
		return handler;
	}

	CameraManager getCameraManager() {
		return cameraManager;
	}

	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		Window window = getWindow();
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.capture);

		hasSurface = false;
		// 初始化电量监控
		inactivityTimer = new InactivityTimer(this);
		// 初始化蜂鸣器
		beepManager = new BeepManager(this);
		// 光源管理
		ambientLightManager = new AmbientLightManager(this);

		PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
	}

	@Override
	protected void onResume() {
		super.onResume();

		// historyManager must be initialized here to update the history
		// preference
		historyManager = new HistoryManager(this);
		historyManager.trimHistory();

		// CameraManager must be initialized here, not in onCreate(). This is
		// necessary because we don't
		// want to open the camera driver and measure the screen size if we're
		// going to show the help on
		// first launch. That led to bugs where the scanning rectangle was the
		// wrong size and partially
		// off screen.
		// 相机管理器 必须在这里初始化
		cameraManager = new CameraManager(getApplication());
		// 扫描口
		viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
		viewfinderView.setCameraManager(cameraManager);
		// 扫描结果
		resultView = findViewById(R.id.result_view);
		// 扫描提示语
		statusView = (TextView) findViewById(R.id.status_view);
		// handler 置空
		handler = null;
		lastResult = null;

		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(this);

		if (prefs.getBoolean(PreferencesActivity.KEY_DISABLE_AUTO_ORIENTATION,
				true)) {
			setRequestedOrientation(getCurrentOrientation());
		} else {
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
		}
		// 重置界面
		resetStatusView();
		// 重置蜂鸣器
		beepManager.updatePrefs();
		// 开启光源管理
		ambientLightManager.start(cameraManager);
		// 重置电量监控
		inactivityTimer.onResume();

		Intent intent = getIntent();
		// 获取粘贴板配置
		copyToClipboard = prefs.getBoolean(
				PreferencesActivity.KEY_COPY_TO_CLIPBOARD, true)
				&& (intent == null || intent.getBooleanExtra(
						Intents.Scan.SAVE_HISTORY, true));
		// Intent 源置为默认
		source = IntentSource.NONE;
		sourceUrl = null;
		scanFromWebPageManager = null;
		// 二维码编码集置空
		decodeFormats = null;
		// 字符编码集
		characterSet = null;

		if (intent != null) {
			// 获取Intent 参数
			String action = intent.getAction();
			String dataString = intent.getDataString();

			if (Intents.Scan.ACTION.equals(action)) {
				// 如果是扫描
				// Scan the formats the intent requested, and return the result
				// to the calling activity.
				// 设置 Intent 源
				source = IntentSource.NATIVE_APP_INTENT;
				// 获取二维码格式
				decodeFormats = DecodeFormatManager.parseDecodeFormats(intent);
				decodeHints = DecodeHintManager.parseDecodeHints(intent);

				if (intent.hasExtra(Intents.Scan.WIDTH)
						&& intent.hasExtra(Intents.Scan.HEIGHT)) {
					int width = intent.getIntExtra(Intents.Scan.WIDTH, 0);
					int height = intent.getIntExtra(Intents.Scan.HEIGHT, 0);
					if (width > 0 && height > 0) {
						cameraManager.setManualFramingRect(width, height);
					}
				}
				// 相机ID 指定前后相机
				if (intent.hasExtra(Intents.Scan.CAMERA_ID)) {
					int cameraId = intent.getIntExtra(Intents.Scan.CAMERA_ID,
							-1);
					if (cameraId >= 0) {
						cameraManager.setManualCameraId(cameraId);
					}
				}

				String customPromptMessage = intent
						.getStringExtra(Intents.Scan.PROMPT_MESSAGE);
				if (customPromptMessage != null) {
					// 提示信息
					statusView.setText(customPromptMessage);
				}

			} else if (dataString != null
					&& dataString.contains("http://www.google")
					&& dataString.contains("/m/products/scan")) {

				// Scan only products and send the result to mobile Product
				// Search.

				// 数据不为空 并且包含链接 搜索 产口
				source = IntentSource.PRODUCT_SEARCH_LINK;
				// 源数据
				sourceUrl = dataString;
				decodeFormats = DecodeFormatManager.PRODUCT_FORMATS;

			} else if (isZXingURL(dataString)) {

				// Scan formats requested in query string (all formats if none
				// specified).
				// If a return URL is specified, send the results there.
				// Otherwise, handle it ourselves.
				// 包含ZXingUrl
				source = IntentSource.ZXING_LINK;
				sourceUrl = dataString;
				Uri inputUri = Uri.parse(dataString);
				scanFromWebPageManager = new ScanFromWebPageManager(inputUri);
				decodeFormats = DecodeFormatManager
						.parseDecodeFormats(inputUri);
				// Allow a sub-set of the hints to be specified by the caller.
				decodeHints = DecodeHintManager.parseDecodeHints(inputUri);

			}
			// 获取编码字符集
			characterSet = intent.getStringExtra(Intents.Scan.CHARACTER_SET);

		}
		// 获取 SurfaceView 预览图像
		SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
		SurfaceHolder surfaceHolder = surfaceView.getHolder();
		if (hasSurface) {
			// The activity was paused but not stopped, so the surface still
			// exists. Therefore
			// surfaceCreated() won't be called, so init the camera here.
			// 初始化相机
			initCamera(surfaceHolder);
		} else {
			// Install the callback and wait for surfaceCreated() to init the
			// camera.
			// 添加回调
			surfaceHolder.addCallback(this);
		}
	}

	/**
	 * 获取 当前手机横竖屏
	 * 
	 * @return
	 */
	private int getCurrentOrientation() {
		int rotation = getWindowManager().getDefaultDisplay().getRotation();
		if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
			switch (rotation) {
			case Surface.ROTATION_0:
			case Surface.ROTATION_90:
				return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
			default:
				return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
			}
		} else {
			switch (rotation) {
			case Surface.ROTATION_0:
			case Surface.ROTATION_270:
				return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
			default:
				return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
			}
		}
	}

	/**
	 * 查看数据是否包含 Zxing URL
	 * 
	 * @param dataString
	 * @return
	 */
	private static boolean isZXingURL(String dataString) {
		if (dataString == null) {
			return false;
		}
		for (String url : ZXING_URLS) {
			if (dataString.startsWith(url)) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected void onPause() {
		if (handler != null) {
			// 退出同步预览
			handler.quitSynchronously();
			handler = null;
		}
		// 电源
		inactivityTimer.onPause();
		// 光源
		ambientLightManager.stop();
		// 蜂鸣器
		beepManager.close();
		// 相机停止驱动
		cameraManager.closeDriver();
		// historyManager = null; // Keep for onActivityResult
		if (!hasSurface) {
			// 重置 hasSurface
			SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
			SurfaceHolder surfaceHolder = surfaceView.getHolder();
			surfaceHolder.removeCallback(this);
		}
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		// 关闭电源监控
		inactivityTimer.shutdown();
		super.onDestroy();
	}

	// 处理返回键
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_BACK:
			// Intent 源为扫描页时 关闭
			if (source == IntentSource.NATIVE_APP_INTENT) {
				setResult(RESULT_CANCELED);
				finish();
				return true;
			}
			// Intent 源为 NoNE 或Zxing链接时 重置预览界面
			if ((source == IntentSource.NONE || source == IntentSource.ZXING_LINK)
					&& lastResult != null) {
				restartPreviewAfterDelay(0L);
				return true;
			}
			break;
		case KeyEvent.KEYCODE_FOCUS:
		case KeyEvent.KEYCODE_CAMERA:
			// Handle these events so they don't launch the Camera app
			return true;
			// Use volume up/down to turn on light
			// 单量键重置相机管理器
		case KeyEvent.KEYCODE_VOLUME_DOWN:
			cameraManager.setTorch(false);
			return true;
		case KeyEvent.KEYCODE_VOLUME_UP:
			cameraManager.setTorch(true);
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// 菜单
		MenuInflater menuInflater = getMenuInflater();
		menuInflater.inflate(R.menu.capture, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
		switch (item.getItemId()) {
		case R.id.menu_share:
			// 分享
			intent.setClassName(this, ShareActivity.class.getName());
			startActivity(intent);
			break;
		case R.id.menu_history:
			// 历史记录 带反回结果
			intent.setClassName(this, HistoryActivity.class.getName());
			startActivityForResult(intent, HISTORY_REQUEST_CODE);
			break;
		case R.id.menu_settings:
			// 设置
			intent.setClassName(this, PreferencesActivity.class.getName());
			startActivity(intent);
			break;
		case R.id.menu_help:
			// 帮助
			intent.setClassName(this, HelpActivity.class.getName());
			startActivity(intent);
			break;
		default:
			return super.onOptionsItemSelected(item);
		}
		return true;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent intent) {
		// 处理历史记录的返回结果
		if (resultCode == RESULT_OK && requestCode == HISTORY_REQUEST_CODE
				&& historyManager != null) {
			int itemNumber = intent
					.getIntExtra(Intents.History.ITEM_NUMBER, -1);
			if (itemNumber >= 0) {
				HistoryItem historyItem = historyManager
						.buildHistoryItem(itemNumber);
				// 显示或保存编码
				decodeOrStoreSavedBitmap(null, historyItem.getResult());
			}
		}
	}

	/**
	 * 编码或保存图片
	 * 
	 * @param bitmap
	 * @param result
	 */
	private void decodeOrStoreSavedBitmap(Bitmap bitmap, Result result) {
		// Bitmap isn't used yet -- will be used soon
		if (handler == null) {
			savedResultToShow = result;
		} else {
			if (result != null) {
				savedResultToShow = result;
			}
			if (savedResultToShow != null) {
				// 解码成功
				Message message = Message.obtain(handler,
						R.id.decode_succeeded, savedResultToShow);
				handler.sendMessage(message);
			}
			savedResultToShow = null;
		}
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		if (holder == null) {
			Log.e(TAG,
					"*** WARNING *** surfaceCreated() gave us a null surface!");
		}
		if (!hasSurface) {
			hasSurface = true;
			initCamera(holder);
		}
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		hasSurface = false;
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {

	}

	/**
	 * 解码成功后处理
	 * A valid barcode has been found, so give an indication of success
	 * and show the results.
	 * 
	 * @param rawResult
	 *            The contents of the barcode.
	 * @param scaleFactor
	 *            amount by which thumbnail was scaled
	 * @param barcode
	 *            A greyscale bitmap of the camera data which was decoded.
	 */
	public void handleDecode(Result rawResult, Bitmap barcode, float scaleFactor) {
		inactivityTimer.onActivity();
		//  最终返回结果
		lastResult = rawResult;
		ResultHandler resultHandler = ResultHandlerFactory.makeResultHandler(
				this, rawResult);
		// 编码不为空
		boolean fromLiveScan = barcode != null;
		if (fromLiveScan) {
			// 添加到历史记录
			historyManager.addHistoryItem(rawResult, resultHandler);
			// Then not from history, so beep/vibrate and we have an image to
			// draw on
			// 播放提示音
			beepManager.playBeepSoundAndVibrate();
			drawResultPoints(barcode, scaleFactor, rawResult);
		}

		switch (source) {
		case NATIVE_APP_INTENT:
		case PRODUCT_SEARCH_LINK:
			handleDecodeExternally(rawResult, resultHandler, barcode);
			break;
		case ZXING_LINK:
			if (scanFromWebPageManager == null
					|| !scanFromWebPageManager.isScanFromWebPage()) {
				handleDecodeInternally(rawResult, resultHandler, barcode);
			} else {
				handleDecodeExternally(rawResult, resultHandler, barcode);
			}
			break;
		case NONE:
			SharedPreferences prefs = PreferenceManager
					.getDefaultSharedPreferences(this);
			if (fromLiveScan
					&& prefs.getBoolean(PreferencesActivity.KEY_BULK_MODE,
							false)) {
				Toast.makeText(
						getApplicationContext(),
						getResources()
								.getString(R.string.msg_bulk_mode_scanned)
								+ " (" + rawResult.getText() + ')',
						Toast.LENGTH_SHORT).show();
				// Wait a moment or else it will scan the same barcode
				// continuously about 3 times
				restartPreviewAfterDelay(BULK_MODE_SCAN_DELAY_MS);
			} else {
				handleDecodeInternally(rawResult, resultHandler, barcode);
			}
			break;
		}
	}

	/**
	 * Superimpose a line for 1D or dots for 2D to highlight the key features of
	 * the barcode.
	 * 
	 * @param barcode
	 *            A bitmap of the captured image.
	 * @param scaleFactor
	 *            amount by which thumbnail was scaled
	 * @param rawResult
	 *            The decoded results which contains the points to draw. 重绘图
	 */
	private void drawResultPoints(Bitmap barcode, float scaleFactor,
			Result rawResult) {
		ResultPoint[] points = rawResult.getResultPoints();
		if (points != null && points.length > 0) {
			Canvas canvas = new Canvas(barcode);
			Paint paint = new Paint();
			paint.setColor(getResources().getColor(R.color.result_points));
			if (points.length == 2) {
				paint.setStrokeWidth(4.0f);
				drawLine(canvas, paint, points[0], points[1], scaleFactor);
			} else if (points.length == 4
					&& (rawResult.getBarcodeFormat() == BarcodeFormat.UPC_A || rawResult
							.getBarcodeFormat() == BarcodeFormat.EAN_13)) {
				// Hacky special case -- draw two lines, for the barcode and
				// metadata
				drawLine(canvas, paint, points[0], points[1], scaleFactor);
				drawLine(canvas, paint, points[2], points[3], scaleFactor);
			} else {
				paint.setStrokeWidth(10.0f);
				for (ResultPoint point : points) {
					if (point != null) {
						canvas.drawPoint(scaleFactor * point.getX(),
								scaleFactor * point.getY(), paint);
					}
				}
			}
		}
	}

	/**
	 * 画线
	 * 
	 * @param canvas
	 * @param paint
	 * @param a
	 * @param b
	 * @param scaleFactor
	 */
	private static void drawLine(Canvas canvas, Paint paint, ResultPoint a,
			ResultPoint b, float scaleFactor) {
		if (a != null && b != null) {
			canvas.drawLine(scaleFactor * a.getX(), scaleFactor * a.getY(),
					scaleFactor * b.getX(), scaleFactor * b.getY(), paint);
		}
	}

	/**
	 * 内部解码 得理解码解果 Put up our own UI for how to handle the decoded contents.
	 * 
	 * @param rawResult
	 * @param resultHandler
	 * @param barcode
	 */
	private void handleDecodeInternally(Result rawResult,
			ResultHandler resultHandler, Bitmap barcode) {
		/**
		 * 要显示的内容
		 */
		CharSequence displayContents = resultHandler.getDisplayContents();

		if (copyToClipboard && !resultHandler.areContentsSecure()) {
			// 向粘贴板复制内容
			ClipboardInterface.setText(displayContents, this);
		}

		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(this);

		if (resultHandler.getDefaultButtonID() != null
				&& prefs.getBoolean(PreferencesActivity.KEY_AUTO_OPEN_WEB,
						false)) {
			resultHandler.handleButtonPress(resultHandler.getDefaultButtonID());
			return;
		}

		statusView.setVisibility(View.GONE);
		viewfinderView.setVisibility(View.GONE);
		// 显示结果面
		resultView.setVisibility(View.VISIBLE);
		// 显示图片
		ImageView barcodeImageView = (ImageView) findViewById(R.id.barcode_image_view);
		if (barcode == null) {
			barcodeImageView.setImageBitmap(BitmapFactory.decodeResource(
					getResources(), R.drawable.launcher_icon));
		} else {
			barcodeImageView.setImageBitmap(barcode);
		}
		// 编码格式
		TextView formatTextView = (TextView) findViewById(R.id.format_text_view);
		formatTextView.setText(rawResult.getBarcodeFormat().toString());
		// 类型
		TextView typeTextView = (TextView) findViewById(R.id.type_text_view);
		typeTextView.setText(resultHandler.getType().toString());
		// 格时化时间
		DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT,
				DateFormat.SHORT);
		TextView timeTextView = (TextView) findViewById(R.id.time_text_view);
		timeTextView
				.setText(formatter.format(new Date(rawResult.getTimestamp())));
		TextView metaTextView = (TextView) findViewById(R.id.meta_text_view);
		// 扫描结果
		View metaTextViewLabel = findViewById(R.id.meta_text_view_label);
		metaTextView.setVisibility(View.GONE);
		metaTextViewLabel.setVisibility(View.GONE);
		Map<ResultMetadataType, Object> metadata = rawResult
				.getResultMetadata();
		if (metadata != null) {
			StringBuilder metadataText = new StringBuilder(20);
			for (Map.Entry<ResultMetadataType, Object> entry : metadata
					.entrySet()) {
				if (DISPLAYABLE_METADATA_TYPES.contains(entry.getKey())) {
					metadataText.append(entry.getValue()).append('\n');
				}
			}
			if (metadataText.length() > 0) {
				metadataText.setLength(metadataText.length() - 1);
				metaTextView.setText(metadataText);
				metaTextView.setVisibility(View.VISIBLE);
				metaTextViewLabel.setVisibility(View.VISIBLE);
			}
		}

		TextView contentsTextView = (TextView) findViewById(R.id.contents_text_view);
		contentsTextView.setText(displayContents);
		int scaledSize = Math.max(22, 32 - displayContents.length() / 4);
		contentsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);

		TextView supplementTextView = (TextView) findViewById(R.id.contents_supplement_text_view);
		supplementTextView.setText("");
		supplementTextView.setOnClickListener(null);
		if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
				PreferencesActivity.KEY_SUPPLEMENTAL, true)) {
			SupplementalInfoRetriever.maybeInvokeRetrieval(supplementTextView,
					resultHandler.getResult(), historyManager, this);
		}

		int buttonCount = resultHandler.getButtonCount();
		ViewGroup buttonView = (ViewGroup) findViewById(R.id.result_button_view);
		buttonView.requestFocus();
		// 显示扫描结果下面的 按钮
		for (int x = 0; x < ResultHandler.MAX_BUTTON_COUNT; x++) {
			TextView button = (TextView) buttonView.getChildAt(x);
			if (x < buttonCount) {
				// 如果有按钮则显示
				button.setVisibility(View.VISIBLE);
				button.setText(resultHandler.getButtonText(x));
				// 处理按钮监听
				button.setOnClickListener(new ResultButtonListener(
						resultHandler, x));
			} else {
				// 否则隐藏按钮
				button.setVisibility(View.GONE);
			}
		}

	}

	// Briefly show the contents of the barcode, then handle the result outside
	// Barcode Scanner.
	/**
	 * 外部解码
	 * 
	 * @param rawResult
	 * @param resultHandler
	 * @param barcode
	 */
	private void handleDecodeExternally(Result rawResult,
			ResultHandler resultHandler, Bitmap barcode) {

		if (barcode != null) {
			viewfinderView.drawResultBitmap(barcode);
		}
		// 延迟时间
		long resultDurationMS;
		if (getIntent() == null) {
			// 15秒
			resultDurationMS = DEFAULT_INTENT_RESULT_DURATION_MS;
		} else {
			// 15秒
			resultDurationMS = getIntent().getLongExtra(
					Intents.Scan.RESULT_DISPLAY_DURATION_MS,
					DEFAULT_INTENT_RESULT_DURATION_MS);
		}

		if (resultDurationMS > 0) {
			String rawResultString = String.valueOf(rawResult);
			if (rawResultString.length() > 32) {
				rawResultString = rawResultString.substring(0, 32) + " ...";
			}
			// 显示提示
			statusView.setText(getString(resultHandler.getDisplayTitle())
					+ " : " + rawResultString);
		}

		if (copyToClipboard && !resultHandler.areContentsSecure()) {
			// 向粘贴板复制
			CharSequence text = resultHandler.getDisplayContents();
			ClipboardInterface.setText(text, this);
		}

		if (source == IntentSource.NATIVE_APP_INTENT) {

			// Hand back whatever action they requested - this can be changed to
			// Intents.Scan.ACTION when
			// the deprecated intent is retired.
			Intent intent = new Intent(getIntent().getAction());
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
			intent.putExtra(Intents.Scan.RESULT, rawResult.toString());
			intent.putExtra(Intents.Scan.RESULT_FORMAT, rawResult
					.getBarcodeFormat().toString());
			byte[] rawBytes = rawResult.getRawBytes();
			if (rawBytes != null && rawBytes.length > 0) {
				intent.putExtra(Intents.Scan.RESULT_BYTES, rawBytes);
			}
			Map<ResultMetadataType, ?> metadata = rawResult.getResultMetadata();
			if (metadata != null) {
				if (metadata.containsKey(ResultMetadataType.UPC_EAN_EXTENSION)) {
					intent.putExtra(Intents.Scan.RESULT_UPC_EAN_EXTENSION,
							metadata.get(ResultMetadataType.UPC_EAN_EXTENSION)
									.toString());
				}
				Number orientation = (Number) metadata
						.get(ResultMetadataType.ORIENTATION);
				if (orientation != null) {
					intent.putExtra(Intents.Scan.RESULT_ORIENTATION,
							orientation.intValue());
				}
				String ecLevel = (String) metadata
						.get(ResultMetadataType.ERROR_CORRECTION_LEVEL);
				if (ecLevel != null) {
					intent.putExtra(Intents.Scan.RESULT_ERROR_CORRECTION_LEVEL,
							ecLevel);
				}
				@SuppressWarnings("unchecked")
				Iterable<byte[]> byteSegments = (Iterable<byte[]>) metadata
						.get(ResultMetadataType.BYTE_SEGMENTS);
				if (byteSegments != null) {
					int i = 0;
					for (byte[] byteSegment : byteSegments) {
						intent.putExtra(
								Intents.Scan.RESULT_BYTE_SEGMENTS_PREFIX + i,
								byteSegment);
						i++;
					}
				}
			}
			sendReplyMessage(R.id.return_scan_result, intent, resultDurationMS);

		} else if (source == IntentSource.PRODUCT_SEARCH_LINK) {

			// Reformulate the URL which triggered us into a query, so that the
			// request goes to the same
			// TLD as the scan URL.
			int end = sourceUrl.lastIndexOf("/scan");
			String replyURL = sourceUrl.substring(0, end) + "?q="
					+ resultHandler.getDisplayContents() + "&source=zxing";
			sendReplyMessage(R.id.launch_product_query, replyURL,
					resultDurationMS);

		} else if (source == IntentSource.ZXING_LINK) {

			if (scanFromWebPageManager != null
					&& scanFromWebPageManager.isScanFromWebPage()) {
				String replyURL = scanFromWebPageManager.buildReplyURL(
						rawResult, resultHandler);
				scanFromWebPageManager = null;
				sendReplyMessage(R.id.launch_product_query, replyURL,
						resultDurationMS);
			}

		}
	}

	/**
	 * 延迟发送 消息
	 * 
	 * @param id
	 * @param arg
	 * @param delayMS
	 */
	private void sendReplyMessage(int id, Object arg, long delayMS) {
		if (handler != null) {
			Message message = Message.obtain(handler, id, arg);
			if (delayMS > 0L) {
				handler.sendMessageDelayed(message, delayMS);
			} else {
				handler.sendMessage(message);
			}
		}
	}

	/**
	 * 初始化相机
	 * 
	 * @param surfaceHolder
	 */
	private void initCamera(SurfaceHolder surfaceHolder) {
		if (surfaceHolder == null) {
			throw new IllegalStateException("No SurfaceHolder provided");
		}
		if (cameraManager.isOpen()) {
			Log.w(TAG,
					"initCamera() while already open -- late SurfaceView callback?");
			return;
		}
		try {
			cameraManager.openDriver(surfaceHolder);
			// Creating the handler starts the preview, which can also throw a
			// RuntimeException.
			if (handler == null) {
				handler = new CaptureActivityHandler(this, decodeFormats,
						decodeHints, characterSet, cameraManager);
			}
			decodeOrStoreSavedBitmap(null, null);
		} catch (IOException ioe) {
			Log.w(TAG, ioe);

			displayFrameworkBugMessageAndExit();
		} catch (RuntimeException e) {
			// Barcode Scanner has seen crashes in the wild of this variety:
			// java.?lang.?RuntimeException: Fail to connect to camera service
			Log.w(TAG, "Unexpected error initializing camera", e);
			displayFrameworkBugMessageAndExit();
		}
	}

	/**
	 * 提示框架Bug 并退出
	 */
	private void displayFrameworkBugMessageAndExit() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(getString(R.string.app_name));
		builder.setMessage(getString(R.string.msg_camera_framework_bug));
		builder.setPositiveButton(R.string.button_ok, new FinishListener(this));
		builder.setOnCancelListener(new FinishListener(this));
		builder.show();
	}

	/**
	 * 重置预览界面
	 * 
	 * @param delayMS
	 */
	public void restartPreviewAfterDelay(long delayMS) {
		if (handler != null) {
			handler.sendEmptyMessageDelayed(R.id.restart_preview, delayMS);
		}
		// 重置界面
		resetStatusView();
	}

	/**
	 * 重置界面
	 */
	private void resetStatusView() {
		// 隐藏扫描结果
		resultView.setVisibility(View.GONE);
		// 重置提示语
		statusView.setText(R.string.msg_default_status);
		// 显示提示语
		statusView.setVisibility(View.VISIBLE);
		// 显示取景框
		viewfinderView.setVisibility(View.VISIBLE);
		lastResult = null;
	}

	/**
	 * 重绘
	 */
	public void drawViewfinder() {
		viewfinderView.drawViewfinder();
	}
}
