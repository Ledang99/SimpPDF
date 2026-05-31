package com.artifex.mupdf.mini;

import com.artifex.mupdf.fitz.*;
import com.artifex.mupdf.fitz.android.*;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileUriExposedException;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Stack;

public class DocumentActivity extends Activity
{
	private final String APP = "MuPDF";

	public final int NAVIGATE_REQUEST = 1;

	protected final int MAXIMUM_OUTLINE_ITEMS = 1000;
	protected final int MAXIMUM_OUTLINE_DEPTH = 4;

	protected final float EXCLUSION_HEIGHT_FACTOR = 2.0f;

	protected Worker worker;
	protected SharedPreferences prefs;

	protected Document doc;

	protected String key;
	protected String mimetype;
	protected SeekableInputStream stream;
	protected byte[] buffer;

	protected boolean returnToLibraryActivity;
	protected boolean hasLoaded;
	protected boolean isReflowable;
	protected boolean fitPage;
	protected String title;
	protected ArrayList<OutlineActivity.Item> flatOutline;
	protected float layoutW, layoutH, layoutEm;
	protected float displayDPI;
	protected int canvasW, canvasH;
	protected float pageZoom;

	protected View currentBar;
	protected PageView pageView;
	protected View actionBar;
	protected TextView titleLabel;
	protected View searchButton;
	protected View searchBar;
	protected EditText searchText;
	protected View searchCloseButton;
	protected View searchBackwardButton;
	protected View searchForwardButton;
	protected View zoomButton;
	protected View layoutButton;
	protected PopupMenu layoutPopupMenu;
	protected View annotateButton;
	protected View outlineButton;
	protected View bottomBar;
	protected View backgroundLayout;
	protected View topBar;
	protected TextView pageLabel;
	protected SeekBar pageSeekbar;

	protected boolean pageCountChanged;
	protected int pageCount;
	protected int currentPage;
	protected int searchHitPage;
	protected String searchNeedle;
	protected boolean stopSearch;
	protected Stack<Integer> history;
	protected boolean wentBack;
	protected boolean toggledUI;
	protected Insets systemInsets = Insets.NONE;
	protected boolean newSearchHitPage;

	protected boolean annotateMode;
	protected float[] annotateColor = {1, 0, 0};
	protected Quad[] currentSelectionQuads;
	protected PopupWindow selectionPopup;

	private String toHex(byte[] digest) {
		StringBuilder builder = new StringBuilder(2 * digest.length);
		for (byte b : digest)
			builder.append(String.format("%02x", b));
		return builder.toString();
	}

	private void openInput(Uri uri, long size, String mimetype) throws IOException {
		ContentResolver cr = getContentResolver();

		Log.i(APP, "Opening document " + uri);

		InputStream is = cr.openInputStream(uri);
		byte[] buf = null;
		int used = -1;
		try {
			final int limit = 8 * 1024 * 1024;
			if (size < 0) { // size is unknown
				buf = new byte[limit];
				used = is.read(buf);
				boolean atEOF = is.read() == -1;
				if (used < 0 || (used == limit && !atEOF)) // no or partial data
					buf = null;
			} else if (size <= limit) { // size is known and below limit
				buf = new byte[(int) size];
				used = is.read(buf);
				if (used < 0 || used < size) // no or partial data
					buf = null;
			}
			if (buf != null && buf.length != used) {
				byte[] newbuf = new byte[used];
				System.arraycopy(buf, 0, newbuf, 0, used);
				buf = newbuf;
			}
		} catch (OutOfMemoryError e) {
			buf = null;
		} finally {
			is.close();
		}

		if (buf != null) {
			Log.i(APP, "  Opening document from memory buffer of size " + buf.length);
			buffer = buf;
		} else {
			Log.i(APP, "  Opening document from stream");
			stream = new ContentInputStream(cr, uri, size);
		}
	}

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

		DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(metrics);
		displayDPI = metrics.densityDpi;

		setContentView(R.layout.document_activity);
		actionBar = findViewById(R.id.action_bar);
		searchBar = findViewById(R.id.search_bar);
		bottomBar = findViewById(R.id.bottom_bar);
		backgroundLayout = findViewById(R.id.background_layout);
		topBar = findViewById(R.id.top_bar);

		currentBar = actionBar;

		Uri uri = getIntent().getData();
		mimetype = getIntent().getType();

		if (uri == null) {
			Toast.makeText(this, getString(R.string.toast_no_document_uri), Toast.LENGTH_SHORT).show();
			return;
		}

		returnToLibraryActivity = getIntent().getIntExtra(getComponentName().getPackageName() + ".ReturnToLibraryActivity", 0) != 0;

		key = uri.toString();

		Log.i(APP, "OPEN URI " + uri.toString());
		Log.i(APP, "  MAGIC (Intent) " + mimetype);

		title = "";
		long size = -1;
		Cursor cursor = null;

		try {
			cursor = getContentResolver().query(uri, null, null, null, null, null);
			if (cursor != null && cursor.moveToFirst()){
				int idx;

				idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_STRING)
					title = cursor.getString(idx);

				idx = cursor.getColumnIndex(OpenableColumns.SIZE);
				if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_INTEGER)
					size = cursor.getLong(idx);

				if (size == 0)
					size = -1;
			}
		} catch (Exception x) {
			// Ignore any exception and depend on default values for title
			// and size (unless one was decoded
		} finally {
			if (cursor != null)
				cursor.close();
		}

		Log.i(APP, "  NAME " + title);
		Log.i(APP, "  SIZE " + size);

		if (mimetype == null || mimetype.equals("application/octet-stream")) {
			mimetype = getContentResolver().getType(uri);
			Log.i(APP, "  MAGIC (Resolver) " + mimetype);
		}
		if (mimetype == null || mimetype.equals("application/octet-stream")) {
			mimetype = title;
			Log.i(APP, "  MAGIC (Filename) " + mimetype);
		}

		try {
			openInput(uri, size, mimetype);
		} catch (Exception x) {
			Log.e(APP, x.toString());
			String text = x.getMessage();
			if (text == null)
				text = x.getClass().getName();
			Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
		}

		titleLabel = (TextView)findViewById(R.id.title_label);
		titleLabel.setText(title);

		history = new Stack<Integer>();

		worker = new Worker(this);
		worker.start();

		prefs = getPreferences(Context.MODE_PRIVATE);
		layoutEm = prefs.getFloat("layoutEm", 8);
		fitPage = prefs.getBoolean("fitPage", false);
		currentPage = prefs.getInt(key, 0);
		searchHitPage = -1;
		hasLoaded = false;

		pageView = (PageView)findViewById(R.id.page_view);
		pageView.setActionListener(this);

		pageLabel = (TextView)findViewById(R.id.page_label);
		pageSeekbar = (SeekBar)findViewById(R.id.page_seekbar);
		pageSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			public int newProgress = -1;
			public void onProgressChanged(SeekBar seekbar, int progress, boolean fromUser) {
				if (fromUser) {
					newProgress = progress;
					showPageNumber(progress + 1);
				}
			}
			public void onStartTrackingTouch(SeekBar seekbar) {}
			public void onStopTrackingTouch(SeekBar seekbar) {
				gotoPage(newProgress);
			}
		});

		searchButton = findViewById(R.id.search_button);
		searchButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showSearch();
			}
		});
		searchText = (EditText)findViewById(R.id.search_text);
		searchText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				if (actionId == EditorInfo.IME_NULL && event.getAction() == KeyEvent.ACTION_DOWN) {
					search(1);
					return true;
				}
				if (actionId == EditorInfo.IME_ACTION_SEARCH) {
					search(1);
					return true;
				}
				return false;
			}
		});
		searchText.addTextChangedListener(new TextWatcher() {
			public void afterTextChanged(Editable s) {}
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				resetSearch();
			}
		});
		searchCloseButton = findViewById(R.id.search_close_button);
		searchCloseButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				hideSearch();
			}
		});
		searchBackwardButton = findViewById(R.id.search_backward_button);
		searchBackwardButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				search(-1);
			}
		});
		searchForwardButton = findViewById(R.id.search_forward_button);
		searchForwardButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				search(1);
			}
		});

		outlineButton = findViewById(R.id.outline_button);
		outlineButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				Intent intent = new Intent(DocumentActivity.this, OutlineActivity.class);
				Bundle bundle = new Bundle();
				bundle.putInt("POSITION", currentPage);
				bundle.putSerializable("OUTLINE", flatOutline);
				intent.putExtras(bundle);
				startActivityForResult(intent, NAVIGATE_REQUEST);
			}
		});

		zoomButton = findViewById(R.id.zoom_button);
		zoomButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				fitPage = !fitPage;
				loadPage();
			}
		});

		layoutButton = findViewById(R.id.layout_button);
		layoutPopupMenu = new PopupMenu(this, layoutButton);
		layoutPopupMenu.getMenuInflater().inflate(R.menu.layout_menu, layoutPopupMenu.getMenu());
		layoutPopupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
			public boolean onMenuItemClick(MenuItem item) {
				float oldLayoutEm = layoutEm;
				int id = item.getItemId();
				if (id == R.id.action_layout_6pt) layoutEm = 6;
				else if (id == R.id.action_layout_7pt) layoutEm = 7;
				else if (id == R.id.action_layout_8pt) layoutEm = 8;
				else if (id == R.id.action_layout_9pt) layoutEm = 9;
				else if (id == R.id.action_layout_10pt) layoutEm = 10;
				else if (id == R.id.action_layout_11pt) layoutEm = 11;
				else if (id == R.id.action_layout_12pt) layoutEm = 12;
				else if (id == R.id.action_layout_13pt) layoutEm = 13;
				else if (id == R.id.action_layout_14pt) layoutEm = 14;
				else if (id == R.id.action_layout_15pt) layoutEm = 15;
				else if (id == R.id.action_layout_16pt) layoutEm = 16;
				if (oldLayoutEm != layoutEm)
					relayoutDocument();
				return true;
			}
		});
		layoutButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				layoutPopupMenu.show();
			}
		});

		annotateButton = findViewById(R.id.annotate_button);
		annotateButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (annotateMode) {
					AlertDialog.Builder builder = new AlertDialog.Builder(DocumentActivity.this);
					builder.setTitle("Annotation");
					String[] options = {"Save", "Undo Stroke", "Redo Stroke", "Cancel"};
					builder.setItems(options, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							switch (which) {
								case 0: // Save
									saveAnnotations();
									break;
								case 1: // Undo
									pageView.undoAnnotate();
									break;
								case 2: // Redo
									pageView.redoAnnotate();
									break;
								case 3: // Cancel
									annotateMode = false;
									pageView.setAnnotateMode(false);
									((ImageButton)annotateButton).setColorFilter(null);
									loadPage();
									break;
							}
						}
					});
					builder.show();
				} else {
					final float[][] colorValues = {{1,0,0}, {1,1,0}, {0,1,0}, {0,0,1}};
					final int[] androidColors = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF0000FF};

					if (selectionPopup != null)
						selectionPopup.dismiss();

					LinearLayout layout = new LinearLayout(DocumentActivity.this);
					layout.setOrientation(LinearLayout.HORIZONTAL);
					layout.setBackgroundColor(0xCCFFFFFF);
					int padding = (int)(10 * displayDPI / 160);
					layout.setPadding(padding, padding, padding, padding);

					for (int i = 0; i < androidColors.length; i++) {
						final int index = i;
						TextView tv = new TextView(DocumentActivity.this);
						tv.setText("");
						tv.setGravity(Gravity.CENTER);
						GradientDrawable gd = new GradientDrawable();
						gd.setShape(GradientDrawable.OVAL);
						gd.setColor(androidColors[i]);
						int size = (int)(40 * displayDPI / 160);
						gd.setSize(size, size);
						tv.setBackground(gd);
						LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
						lp.setMargins(padding, 0, padding, 0);
						tv.setLayoutParams(lp);
						tv.setOnClickListener(new View.OnClickListener() {
							public void onClick(View v) {
								annotateColor = colorValues[index];
								annotateMode = true;
								pageView.setAnnotateMode(true);
								pageView.setAnnotateColor(androidColors[index]);
								((ImageButton)annotateButton).setColorFilter(androidColors[index]);
								Toast.makeText(DocumentActivity.this, "Annotation mode: ON", Toast.LENGTH_SHORT).show();
								selectionPopup.dismiss();
							}
						});
						layout.addView(tv);
					}

					selectionPopup = new PopupWindow(layout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
					selectionPopup.setOutsideTouchable(true);
					selectionPopup.showAtLocation(pageView, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, (int)(100 * displayDPI / 160));
				}
			}
		});

		topBar.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
				public WindowInsets onApplyWindowInsets(View v, WindowInsets windowInsets)
				{
					applyInsets(windowInsets);
					return WindowInsets.CONSUMED;
				}
		});

		if (Build.VERSION.SDK_INT >= 29)
			bottomBar.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
				public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
					View parent = (View) v.getParent();
					android.graphics.Rect exclusion;

					exclusion = new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight());
					v.setSystemGestureExclusionRects(Collections.singletonList(exclusion));

					int extended_top = parent.getHeight() - (int) (EXCLUSION_HEIGHT_FACTOR * v.getHeight());
					exclusion = new android.graphics.Rect(0, extended_top, parent.getWidth(), parent.getHeight());
					parent.setSystemGestureExclusionRects(Collections.singletonList(exclusion));
				}
			});
	}

	protected void saveAnnotations() {
		final Point[][] inkList = pageView.getInkList();
		if (inkList == null) {
			annotateMode = false;
			pageView.setAnnotateMode(false);
			((ImageButton)annotateButton).setColorFilter(null);
			return;
		}

		worker.add(new Worker.Task() {
			public void work() {
				PDFDocument pdf = doc.asPDF();
				if (pdf != null) {
					PDFPage page = (PDFPage) pdf.loadPage(currentPage);
					PDFAnnotation annot = page.createAnnotation(PDFAnnotation.TYPE_INK);
					annot.setInkList(inkList);
					annot.setColor(annotateColor);
					annot.update();
					page.update();
					page.destroy();
					saveDocument();
				}
			}
			public void run() {
				annotateMode = false;
				pageView.setAnnotateMode(false);
				((ImageButton)annotateButton).setColorFilter(null);
				loadPage();
			}
		});
	}

	protected void saveDocument() {
		final PDFDocument pdf = doc.asPDF();
		if (pdf != null) {
			worker.add(new Worker.Task() {
				boolean success = false;
				public void work() {
					Uri uri = getIntent().getData();
					try {
						if ("file".equals(uri.getScheme())) {
							try {
								pdf.save(uri.getPath(), "incremental");
							} catch (Exception e) {
								pdf.save(uri.getPath(), "de-linearize");
							}
						} else {
							ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw");
							FileDescriptorStream stream = new FileDescriptorStream(pfd);
							try {
								pdf.save(stream, "incremental");
							} catch (Exception e) {
								try { stream.close(); } catch (Exception e2) {}
								pfd = getContentResolver().openFileDescriptor(uri, "rwt");
								stream = new FileDescriptorStream(pfd);
								pdf.save(stream, "de-linearize");
							} finally {
								try { stream.close(); } catch (Exception e2) {}
							}
						}
						success = true;
					} catch (Exception e) {
						Log.e(APP, "saveDocument: " + e.getMessage());
					}
				}
				public void run() {
					if (success) {
						Toast.makeText(DocumentActivity.this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show();
					} else {
						Toast.makeText(DocumentActivity.this, getString(R.string.toast_save_failed), Toast.LENGTH_SHORT).show();
					}
				}
			});
		}
	}

	protected void showPageNumber(int pageNumber) {
		if (pageCountChanged && bottomBar.getVisibility() == View.VISIBLE)
		{
			// set width of longest possible text as minimum width this
			// ensures that the size of pageLabel doesn't change whether
			// it renders 1 / pageCount or pageCount / pageCount,
			// which makes pageSeekbar to the left of pageLabel also have
			// a stable width.

			TextPaint paint = pageLabel.getPaint();
			float maxWidth =
				pageLabel.getPaddingLeft() +
				paint.measureText(pageCount + " / " + pageCount) + // longest possible text
				pageLabel.getPaddingRight();
			int minWidth = (int) Math.ceil(maxWidth);
			if (minWidth > 0)
			{
				pageLabel.setMinWidth(minWidth);
				pageCountChanged = false;
			}
		}

		pageLabel.setText(pageNumber + " / " + pageCount);
	}

	protected void applyInsets(WindowInsets windowInsets) {
		systemInsets = Insets.NONE;
		Insets systemBarInsets = windowInsets.getInsets(WindowInsets.Type.systemBars());
		systemInsets = Insets.max(systemInsets, systemBarInsets);
		Insets cutoutInsets = windowInsets.getInsets(WindowInsets.Type.displayCutout());
		systemInsets = Insets.max(systemInsets, cutoutInsets);
		topBar.setPadding(0, systemInsets.top, 0, 0);
		bottomBar.setPadding(0, 0, 0, systemInsets.bottom);
	}

	public boolean onKeyUp(int keyCode, KeyEvent event) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_PAGE_UP:
		case KeyEvent.KEYCODE_COMMA:
		case KeyEvent.KEYCODE_B:
			goBackward();
			return true;
		case KeyEvent.KEYCODE_PAGE_DOWN:
		case KeyEvent.KEYCODE_PERIOD:
		case KeyEvent.KEYCODE_SPACE:
			goForward();
			return true;
		case KeyEvent.KEYCODE_M:
			history.push(currentPage);
			return true;
		case KeyEvent.KEYCODE_T:
			if (!history.empty()) {
				currentPage = history.pop();
				loadPage();
			}
			return true;
		}
		return super.onKeyUp(keyCode, event);
	}

	public void onPageViewSizeChanged(int w, int h) {
		pageZoom = 1;
		canvasW = w;
		canvasH = h;
		layoutW = canvasW * 72 / displayDPI;
		layoutH = canvasH * 72 / displayDPI;
		if (!hasLoaded) {
			hasLoaded = true;
			openDocument();
		} else if (isReflowable) {
			relayoutDocument();
		} else {
			loadPage();
		}
	}

	public void onPageViewZoomChanged(float zoom) {
		if (zoom != pageZoom) {
			pageZoom = zoom;
			loadPage();
		}
	}

	public void onSelectionChanged(final float x0, final float y0, final float x1, final float y1) {
		worker.add(new Worker.Task() {
			Quad[] quads;
			public void work() {
				Page page = doc.loadPage(currentPage);
				StructuredText st = page.toStructuredText();
				Matrix ctm;
				if (fitPage)
					ctm = AndroidDrawDevice.fitPage(page, canvasW, canvasH);
				else
					ctm = AndroidDrawDevice.fitPageWidth(page, canvasW);
				Matrix inv = new Matrix(ctm).invert();
				Point p0 = new Point(x0, y0).transform(inv);
				Point p1 = new Point(x1, y1).transform(inv);
				if (x0 == x1 && y0 == y1) {
					Quad q = st.snapSelection(p0, p1, StructuredText.SELECT_WORDS);
					if (q != null)
						quads = new Quad[]{q};
				} else {
					quads = st.highlight(p0, p1);
				}
				st.destroy();
				if (quads != null) {
					for (Quad q : quads)
						q.transform(ctm);
				}
				page.destroy();
			}
			public void run() {
				currentSelectionQuads = quads;
				pageView.setSelection(quads);
			}
		});
	}

	public void onSelectionEnd() {
		if (currentSelectionQuads != null && currentSelectionQuads.length > 0) {
			showSelectionMenu();
		}
	}

	public boolean onAnnotationLongPress(final float x, final float y) {
		final PDFDocument pdf = doc.asPDF();
		if (pdf == null) return false;

		Page page = doc.loadPage(currentPage);
		Matrix ctm;
		if (fitPage)
			ctm = AndroidDrawDevice.fitPage(page, canvasW, canvasH);
		else
			ctm = AndroidDrawDevice.fitPageWidth(page, canvasW);
		final Point p = new Point(x, y).transform(new Matrix(ctm).invert());

		PDFPage pdfPage = (PDFPage) pdf.loadPage(currentPage);
		PDFAnnotation[] annots = pdfPage.getAnnotations();
		PDFAnnotation hit = null;
		if (annots != null) {
			for (PDFAnnotation annot : annots) {
				if (annot.getBounds().contains(p.x, p.y)) {
					hit = annot;
					break;
				}
			}
		}
		pdfPage.destroy();
		page.destroy();

		if (hit != null) {
			showAnnotationMenu(hit);
			return true;
		}

		return false;
	}

	private void showAnnotationMenu(final PDFAnnotation annot) {
		if (selectionPopup != null)
			selectionPopup.dismiss();

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.HORIZONTAL);
		layout.setBackgroundColor(0xCCFFFFFF);
		int padding = (int)(10 * displayDPI / 160);
		layout.setPadding(padding, padding, padding, padding);

		TextView delete = new TextView(this);
		delete.setText("X");
		delete.setGravity(Gravity.CENTER);
		delete.setTextColor(Color.WHITE);
		GradientDrawable dgd = new GradientDrawable();
		dgd.setShape(GradientDrawable.OVAL);
		dgd.setColor(Color.RED);
		int size = (int)(40 * displayDPI / 160);
		dgd.setSize(size, size);
		delete.setBackground(dgd);
		LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(size, size);
		dlp.setMargins(padding, 0, padding, 0);
		delete.setLayoutParams(dlp);
		delete.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				deleteAnnotation(annot);
				selectionPopup.dismiss();
			}
		});
		layout.addView(delete);

		TextView cancel = new TextView(this);
		cancel.setText("O");
		cancel.setGravity(Gravity.CENTER);
		cancel.setTextColor(Color.WHITE);
		GradientDrawable cgd = new GradientDrawable();
		cgd.setShape(GradientDrawable.OVAL);
		cgd.setColor(Color.BLACK);
		cgd.setSize(size, size);
		cancel.setBackground(cgd);
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(size, size);
		clp.setMargins(padding, 0, padding, 0);
		cancel.setLayoutParams(clp);
		cancel.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				selectionPopup.dismiss();
			}
		});
		layout.addView(cancel);

		selectionPopup = new PopupWindow(layout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		selectionPopup.setOutsideTouchable(true);
		selectionPopup.showAtLocation(pageView, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, (int)(100 * displayDPI / 160));
	}

	protected void deleteAnnotation(final PDFAnnotation annot) {
		worker.add(new Worker.Task() {
			public void work() {
				PDFDocument pdf = doc.asPDF();
				if (pdf != null) {
					PDFPage page = (PDFPage) pdf.loadPage(currentPage);
					page.deleteAnnotation(annot);
					page.update();
					saveDocument();
					page.destroy();
				}
			}
			public void run() {
				loadPage();
			}
		});
	}

	public void dismissSelection() {
		currentSelectionQuads = null;
		pageView.setSelection(null);
		if (selectionPopup != null)
			selectionPopup.dismiss();
	}

	private void showSelectionMenu() {
		if (selectionPopup != null)
			selectionPopup.dismiss();

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.HORIZONTAL);
		layout.setBackgroundColor(0xCCFFFFFF);
		int padding = (int)(10 * displayDPI / 160);
		layout.setPadding(padding, padding, padding, padding);

		final int[] colors = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF0000FF}; // Red, Yellow, Green, Blue
		final float[][] colorValues = {{1,0,0}, {1,1,0}, {0,1,0}, {0,0,1}};

		for (int i = 0; i < colors.length; i++) {
			final int index = i;
			TextView tv = new TextView(this);
			tv.setText("");
			tv.setGravity(Gravity.CENTER);
			GradientDrawable gd = new GradientDrawable();
			gd.setShape(GradientDrawable.OVAL);
			gd.setColor(colors[i]);
			int size = (int)(40 * displayDPI / 160);
			gd.setSize(size, size);
			tv.setBackground(gd);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
			lp.setMargins(padding, 0, padding, 0);
			tv.setLayoutParams(lp);
			tv.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					highlightSelectedWord(colorValues[index]);
					selectionPopup.dismiss();
				}
			});
			layout.addView(tv);
		}

		TextView undo = new TextView(this);
		undo.setText("U");
		undo.setGravity(Gravity.CENTER);
		undo.setTextColor(Color.WHITE);
		GradientDrawable ugd = new GradientDrawable();
		ugd.setShape(GradientDrawable.OVAL);
		ugd.setColor(Color.GRAY);
		int size = (int)(40 * displayDPI / 160);
		ugd.setSize(size, size);
		undo.setBackground(ugd);
		LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(size, size);
		ulp.setMargins(padding, 0, padding, 0);
		undo.setLayoutParams(ulp);
		undo.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				undoLastAnnotation();
				selectionPopup.dismiss();
			}
		});
		layout.addView(undo);

		TextView cancel = new TextView(this);
		cancel.setText("X");
		cancel.setGravity(Gravity.CENTER);
		cancel.setTextColor(Color.WHITE);
		GradientDrawable cgd = new GradientDrawable();
		cgd.setShape(GradientDrawable.OVAL);
		cgd.setColor(Color.BLACK);
		cancel.setBackground(cgd);
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(size, size);
		clp.setMargins(padding, 0, padding, 0);
		cancel.setLayoutParams(clp);
		cancel.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				dismissSelection();
			}
		});
		layout.addView(cancel);

		selectionPopup = new PopupWindow(layout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		selectionPopup.setOutsideTouchable(true);
		selectionPopup.showAtLocation(pageView, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, (int)(100 * displayDPI / 160));
	}

	protected void highlightSelectedWord(final float[] color) {
		if (currentSelectionQuads == null) return;
		worker.add(new Worker.Task() {
			public void work() {
				PDFDocument pdf = doc.asPDF();
				if (pdf != null) {
					Page page = doc.loadPage(currentPage);
					Matrix ctm;
					if (fitPage)
						ctm = AndroidDrawDevice.fitPage(page, canvasW, canvasH);
					else
						ctm = AndroidDrawDevice.fitPageWidth(page, canvasW);

					Matrix inv = new Matrix(ctm).invert();
					Quad[] qs = new Quad[currentSelectionQuads.length];
					for (int i = 0; i < currentSelectionQuads.length; i++)
						qs[i] = currentSelectionQuads[i].transformed(inv);

					PDFPage pdfPage = (PDFPage) pdf.loadPage(currentPage);
					PDFAnnotation annot = pdfPage.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT);
					annot.setQuadPoints(qs);
					annot.setColor(color);
					annot.update();
					pdfPage.update();
					pdfPage.destroy();
					saveDocument();
					page.destroy();
				}
			}
			public void run() {
				dismissSelection();
				loadPage();
			}
		});
	}

	protected void undoLastAnnotation() {
		worker.add(new Worker.Task() {
			public void work() {
				PDFDocument pdf = doc.asPDF();
				if (pdf != null) {
					PDFPage page = (PDFPage) pdf.loadPage(currentPage);
					PDFAnnotation[] annots = page.getAnnotations();
					if (annots != null && annots.length > 0) {
						page.deleteAnnotation(annots[annots.length - 1]);
						page.update();
						saveDocument();
					}
					page.destroy();
				}
			}
			public void run() {
				dismissSelection();
				loadPage();
			}
		});
	}

	protected void openDocument() {
		worker.add(new Worker.Task() {
			boolean needsPassword;
			public void work() {
				Log.i(APP, "open document");
				if (buffer != null)
					doc = Document.openDocument(buffer, mimetype);
				else
					doc = Document.openDocument(stream, mimetype);
				needsPassword = doc.needsPassword();
			}
			public void run() {
				if (needsPassword)
					askPassword(R.string.dlog_password_message);
				else
					loadDocument();
			}
		});
	}

	protected void askPassword(int message) {
		final EditText passwordView = new EditText(this);
		passwordView.setInputType(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
		passwordView.setTransformationMethod(PasswordTransformationMethod.getInstance());

		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.dlog_password_title);
		builder.setMessage(message);
		builder.setView(passwordView);
		builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				checkPassword(passwordView.getText().toString());
			}
		});
		builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				finish();
			}
		});
		builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
			public void onCancel(DialogInterface dialog) {
				finish();
			}
		});
		builder.create().show();
	}

	protected void checkPassword(final String password) {
		worker.add(new Worker.Task() {
			boolean passwordOkay;
			public void work() {
				Log.i(APP, "check password");
				passwordOkay = doc.authenticatePassword(password);
			}
			public void run() {
				if (passwordOkay)
					loadDocument();
				else
					askPassword(R.string.dlog_password_retry);
			}
		});
	}

	public void onPause() {
		super.onPause();
		if (prefs != null) {
			SharedPreferences.Editor editor = prefs.edit();
			editor.putFloat("layoutEm", layoutEm);
			editor.putBoolean("fitPage", fitPage);
			editor.putInt(key, currentPage);
			editor.apply();
		}
	}

	public void onBackPressed() {
		if (annotateMode) {
			annotateMode = false;
			pageView.setAnnotateMode(false);
			((ImageButton)annotateButton).setColorFilter(null);
			return;
		}
		if (history.empty()) {
			super.onBackPressed();
			if (returnToLibraryActivity) {
				Intent intent = getPackageManager().getLaunchIntentForPackage(getComponentName().getPackageName());
				startActivity(intent);
			}
		} else {
			currentPage = history.pop();
			loadPage();
		}
	}

	public void onActivityResult(int request, int result, Intent data) {
		if (request == NAVIGATE_REQUEST && result >= RESULT_FIRST_USER)
			gotoPage(result - RESULT_FIRST_USER);
	}

	protected void showKeyboard() {
		InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.showSoftInput(searchText, 0);
	}

	protected void hideKeyboard() {
		InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.hideSoftInputFromWindow(searchText.getWindowToken(), 0);
	}

	protected void resetSearch() {
		stopSearch = true;
		searchHitPage = -1;
		searchNeedle = null;
		pageView.resetHits();
	}

	protected void runSearch(final int startPage, final int direction, final String needle) {
		stopSearch = false;
		worker.add(new Worker.Task() {
			int searchPage = startPage;
			public void work() {
				if (stopSearch || needle != searchNeedle)
					return;
				for (int i = 0; i < 9; ++i) {
					Log.i(APP, "search page " + searchPage);
					Page page = doc.loadPage(searchPage);
					Quad[][] hits = page.search(searchNeedle);
					page.destroy();
					if (hits != null && hits.length > 0) {
						newSearchHitPage = true;
						searchHitPage = searchPage;
						break;
					}
					searchPage += direction;
					if (searchPage < 0 || searchPage >= pageCount)
						break;
				}
			}
			public void run() {
				if (stopSearch || needle != searchNeedle) {
					showPageNumber(currentPage + 1);
				} else if (searchHitPage == currentPage) {
					loadPage();
				} else if (searchHitPage >= 0) {
					history.push(currentPage);
					currentPage = searchHitPage;
					loadPage();
				} else {
					if (searchPage >= 0 && searchPage < pageCount) {
						showPageNumber(searchPage + 1);
						worker.add(this);
					} else {
						showPageNumber(currentPage + 1);
						Log.i(APP, "search not found");
						Toast.makeText(DocumentActivity.this, getString(R.string.toast_search_not_found), Toast.LENGTH_SHORT).show();
					}
				}
			}
		});
	}

	protected void search(int direction) {
		hideKeyboard();
		int startPage;
		if (searchHitPage == currentPage)
			startPage = currentPage + direction;
		else
			startPage = currentPage;
		searchHitPage = -1;
		searchNeedle = searchText.getText().toString();
		if (searchNeedle.length() == 0)
			searchNeedle = null;
		if (searchNeedle != null)
			if (startPage >= 0 && startPage < pageCount)
				runSearch(startPage, direction, searchNeedle);
	}

	protected void loadDocument() {
		worker.add(new Worker.Task() {
			public void work() {
				try {
					Log.i(APP, "load document");
					String metaTitle = doc.getMetaData(Document.META_INFO_TITLE);
					if (metaTitle != null && !metaTitle.equals(""))
						title = metaTitle;
					isReflowable = doc.isReflowable();
					if (isReflowable) {
						Log.i(APP, "layout document");
						doc.layout(layoutW, layoutH, layoutEm);
					}
					pageCount = doc.countPages();
					pageCountChanged = true;
				} catch (Throwable x) {
					doc = null;
					pageCount = 1;
					pageCountChanged = true;
					currentPage = 0;
					throw x;
				}
			}
			public void run() {
				pageCountChanged = true;
				if (currentPage < 0 || currentPage >= pageCount)
					currentPage = 0;
				titleLabel.setText(title);
				if (isReflowable)
					layoutButton.setVisibility(View.VISIBLE);
				else
					zoomButton.setVisibility(View.VISIBLE);
				if (doc.asPDF() != null)
					annotateButton.setVisibility(View.VISIBLE);
				loadPage();
				loadOutline();
			}
		});
	}

	protected void relayoutDocument() {
		worker.add(new Worker.Task() {
			public void work() {
				try {
					long mark = doc.makeBookmark(doc.locationFromPageNumber(currentPage));
					Log.i(APP, "relayout document");
					doc.layout(layoutW, layoutH, layoutEm);
					pageCount = doc.countPages();
					currentPage = doc.pageNumberFromLocation(doc.findBookmark(mark));
				} catch (Throwable x) {
					pageCount = 1;
					currentPage = 0;
					throw x;
				}
			}
			public void run() {
				history.clear();
				pageCountChanged = true;
				loadPage();
				loadOutline();
			}
		});
	}

	private void loadOutline() {
		worker.add(new Worker.Task() {
			boolean outlineTruncated = false;
			private void flattenOutline(Outline[] outline, String indent, int depth) {
				for (Outline node : outline) {
					if (node.title != null)
					{
						int outlinePage = doc.pageNumberFromLocation(doc.resolveLink(node));
						if (flatOutline.size() >= MAXIMUM_OUTLINE_ITEMS)
							outlineTruncated = true;
						else
							flatOutline.add(new OutlineActivity.Item(indent + node.title, node.uri, outlinePage));
					}
					if (node.down != null)
					{
						if (depth >= MAXIMUM_OUTLINE_DEPTH || flatOutline.size() >= MAXIMUM_OUTLINE_ITEMS)
							outlineTruncated = true;
						else
							flattenOutline(node.down, indent + "    ", depth + 1);
					}
				}
			}
			public void work() {
				Log.i(APP, "load outline");
				Outline[] outline = doc.loadOutline();
				if (outline != null) {
					flatOutline = new ArrayList<OutlineActivity.Item>();
					flattenOutline(outline, "", 0);
				} else {
					flatOutline = null;
				}
			}
			public void run() {
				if (flatOutline != null)
					outlineButton.setVisibility(View.VISIBLE);
				if (outlineTruncated)
					Toast.makeText(DocumentActivity.this, getString(R.string.toast_outline_too_large), Toast.LENGTH_SHORT).show();
			}
		});
	}

	protected void loadPage() {
		final int pageNumber = currentPage;
		final float zoom = pageZoom;
		stopSearch = true;
		worker.add(new Worker.Task() {
			public Bitmap bitmap;
			public Rect[] linkBounds;
			public String[] linkURIs;
			public Quad[][] hits;
			public Matrix pageCtm;
			public void work() {
				try {
					Log.i(APP, "load page " + pageNumber);
					Page page = doc.loadPage(pageNumber);
					Log.i(APP, "draw page " + pageNumber + " zoom=" + zoom);
					Matrix ctm;
					if (fitPage)
						ctm = AndroidDrawDevice.fitPage(page, canvasW, canvasH);
					else
						ctm = AndroidDrawDevice.fitPageWidth(page, canvasW);
					pageCtm = new Matrix(ctm);
					Link[] links = page.getLinks();
					if (links == null)
					{
						linkBounds = new Rect[0];
						linkURIs = new String[0];
					}
					else
					{
						linkBounds = new Rect[links.length];
						linkURIs = new String[links.length];
						for (int i = 0; i < links.length; i++)
						{
							linkBounds[i] = links[i].getBounds().transform(ctm);
							linkURIs[i] = links[i].getURI();
						}
					}
					if (searchNeedle != null) {
						hits = page.search(searchNeedle);
						if (hits != null)
							for (Quad[] hit : hits)
								for (Quad chr : hit)
									chr.transform(ctm);
					}
					if (zoom != 1)
						ctm.scale(zoom);
					bitmap = AndroidDrawDevice.drawPage(page, ctm);
				} catch (Throwable x) {
					Log.e(APP, x.getMessage());
				}
			}
			public void run() {
				if (bitmap != null) {
					pageView.setPageTransform(pageCtm);
					pageView.setBitmap(bitmap, zoom, wentBack, toggledUI, newSearchHitPage, linkBounds, linkURIs, hits);
				} else {
					pageView.setError();
				}
				showPageNumber(currentPage + 1);
				pageSeekbar.setMax(pageCount - 1);
				pageSeekbar.setProgress(pageNumber);
				wentBack = false;
				toggledUI = false;
				newSearchHitPage = false;
			}
		});
	}

	protected void showSearch() {
		currentBar = searchBar;
		actionBar.setVisibility(View.GONE);
		searchBar.setVisibility(View.VISIBLE);
		searchBar.requestFocus();
		showKeyboard();
	}

	protected void hideSearch() {
		currentBar = actionBar;
		actionBar.setVisibility(View.VISIBLE);
		searchBar.setVisibility(View.GONE);
		hideKeyboard();
		resetSearch();
	}

	public void toggleUI() {
		toggledUI = true;
		if (bottomBar.getVisibility() == View.VISIBLE) {
			topBar.setVisibility(View.GONE);
			currentBar.setVisibility(View.GONE);
			bottomBar.setVisibility(View.GONE);
			if (currentBar == searchBar)
				hideKeyboard();
		} else {
			topBar.setVisibility(View.VISIBLE);
			currentBar.setVisibility(View.VISIBLE);
			bottomBar.setVisibility(View.VISIBLE);
			showPageNumber(currentPage + 1);
			if (currentBar == searchBar) {
				searchBar.requestFocus();
				showKeyboard();
			}
		}
	}

	public void goBackward() {
		if (currentPage > 0) {
			wentBack = true;
			currentPage --;
			loadPage();
		}
	}

	public void goForward() {
		if (currentPage < pageCount - 1) {
			currentPage ++;
			loadPage();
		}
	}

	public void gotoPage(int p) {
		if (p >= 0 && p < pageCount && p != currentPage) {
			history.push(currentPage);
			currentPage = p;
			loadPage();
		}
	}

	public void gotoPage(String uri) {
		gotoPage(doc.pageNumberFromLocation(doc.resolveLink(uri)));
	}

	public void gotoURI(String uri) {
		Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET); // FLAG_ACTIVITY_NEW_DOCUMENT in API>=21
		try {
			startActivity(intent);
		} catch (FileUriExposedException x) {
			Log.e(APP, x.toString());
			Toast.makeText(DocumentActivity.this, getString(R.string.toast_file_uris_not_allowed) + uri, Toast.LENGTH_LONG).show();
		} catch (Throwable x) {
			Log.e(APP, x.getMessage());
			Toast.makeText(DocumentActivity.this, x.getMessage(), Toast.LENGTH_SHORT).show();
		}
	}
}
