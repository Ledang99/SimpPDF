package com.artifex.mupdf.mini;

import com.artifex.mupdf.fitz.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Scroller;

import java.util.ArrayList;

public class PageView extends View implements
	GestureDetector.OnGestureListener,
	ScaleGestureDetector.OnScaleGestureListener
{
	private final String APP = "MuPDF";

	protected DocumentActivity actionListener;

	protected float pageScale, viewScale, minScale, maxScale;
	protected Bitmap bitmap;
	protected int bitmapW, bitmapH;
	protected int canvasW, canvasH;
	protected int scrollX, scrollY;
	protected Rect[] linkBounds;
	protected String[] linkURIs;
	protected Quad[][] hits;
	protected Quad[] selectedQuads;
	protected Paint selectionPaint;
	protected boolean showLinks;

	protected boolean isSelecting;
	protected Point selectionStart;
	protected Point selectionEnd;

	protected GestureDetector detector;
	protected ScaleGestureDetector scaleDetector;
	protected Scroller scroller;
	protected boolean error;
	protected Paint errorPaint;
	protected Path errorPath;
	protected Paint linkPaint;
	protected Paint hitPaint;

	protected boolean annotateMode;
	protected Paint inkPaint;
	protected Paint themePaint;
	protected ArrayList<ArrayList<Point>> inkList;
	protected ArrayList<ArrayList<Point>> redoList;
	protected ArrayList<Point> currentStroke;
	protected Matrix ctm;
	protected Matrix invCtm;
	private Path tempPath = new Path();

	public PageView(Context ctx, AttributeSet atts) {
		super(ctx, atts);

		scroller = new Scroller(ctx);
		detector = new GestureDetector(ctx, this);
		scaleDetector = new ScaleGestureDetector(ctx, this);

		pageScale = 1;
		viewScale = 1;
		minScale = 1;
		maxScale = 8;

		linkPaint = new Paint();
		linkPaint.setARGB(32, 0, 0, 255);

		hitPaint = new Paint();
		hitPaint.setARGB(32, 255, 0, 0);
		hitPaint.setStyle(Paint.Style.FILL);

		selectionPaint = new Paint();
		selectionPaint.setARGB(64, 0, 128, 255);
		selectionPaint.setStyle(Paint.Style.FILL);

		errorPaint = new Paint();
		errorPaint.setARGB(255, 255, 80, 80);
		errorPaint.setStrokeWidth(5);
		errorPaint.setStyle(Paint.Style.STROKE);

		errorPath = new Path();
		errorPath.moveTo(-100, -100);
		errorPath.lineTo(100, 100);
		errorPath.moveTo(100, -100);
		errorPath.lineTo(-100, 100);

		inkPaint = new Paint();
		inkPaint.setColor(Color.RED);
		inkPaint.setStyle(Paint.Style.STROKE);
		inkPaint.setStrokeWidth(5);
		inkPaint.setStrokeCap(Paint.Cap.ROUND);
		inkPaint.setStrokeJoin(Paint.Join.ROUND);
		inkPaint.setAntiAlias(true);

		themePaint = new Paint();
		themePaint.setAntiAlias(true);
		themePaint.setFilterBitmap(true);

		inkList = new ArrayList<>();
		redoList = new ArrayList<>();
	}

	public void setActionListener(DocumentActivity l) {
		actionListener = l;
	}

	public synchronized void setError() {
		if (bitmap != null)
			bitmap.recycle();
		error = true;
		linkBounds = new Rect[0];
		linkURIs = new String[0];
		hits = null;
		bitmap = null;
		scroller.forceFinished(true);
		invalidate();
	}

	public synchronized void setBitmap(Bitmap b, float zoom, boolean wentBack, boolean toggledUI, boolean scrollToFirstSearchHit, Rect[] lbs, String[] lus, Quad[][] hs) {
		if (bitmap != null)
			bitmap.recycle();
		error = false;
		linkBounds = lbs;
		linkURIs = lus;
		hits = hs;
		bitmap = b;
		bitmapW = (int)(bitmap.getWidth() * viewScale / zoom);
		bitmapH = (int)(bitmap.getHeight() * viewScale / zoom);
		scroller.forceFinished(true);
		if (scrollToFirstSearchHit && hits != null)
		{
			float top = bitmapH;
			float left = bitmapW;

			for (Quad[] hit : hits)
			{
				for (Quad q : hit) {
					if (q.ul_x * viewScale < left) left = q.ul_x * viewScale;
					if (q.ll_x * viewScale < left) left = q.ll_x * viewScale;
					if (q.lr_x * viewScale < left) left = q.lr_x * viewScale;
					if (q.ur_x * viewScale < left) left = q.ur_x * viewScale;

					if (q.ul_y * viewScale < top) top = q.ul_y * viewScale;
					if (q.ll_y * viewScale < top) top = q.ll_y * viewScale;
					if (q.lr_y * viewScale < top) top = q.lr_y * viewScale;
					if (q.ur_y * viewScale < top) top = q.ur_y * viewScale;
				}
			}

			scrollX = (int) (left + 0.5f) - canvasW / 2;
			scrollY = (int) (top + 0.5f) - canvasH / 2;

			if (scrollX < 0) scrollX = 0;
			if (scrollY < 0) scrollY = 0;
			if (scrollX > bitmapW - canvasW) scrollX = bitmapW - canvasW;
			if (scrollY > bitmapW - canvasW) scrollY = bitmapW - canvasW;
		}
		else if (!toggledUI && pageScale == zoom)
		{
			scrollX = wentBack ? bitmapW - canvasW : 0;
			scrollY = wentBack ? bitmapH - canvasH : 0;
		}
		pageScale = zoom;
		inkList.clear();
		invalidate();
	}

	public void resetHits() {
		hits = null;
		invalidate();
	}

	public void onSizeChanged(int w, int h, int ow, int oh) {
		canvasW = w;
		canvasH = h;
		if (actionListener != null)
			actionListener.onPageViewSizeChanged(w, h);
	}

	public void setPageTransform(Matrix ctm) {
		this.ctm = ctm;
		this.invCtm = new Matrix(ctm).invert();
	}

	public boolean onTouchEvent(MotionEvent event) {
		if (annotateMode && invCtm != null) {
			float x = event.getX();
			float y = event.getY();
			float px = (x - getOffsetX()) / viewScale;
			float py = (y - getOffsetY()) / viewScale;
			Point p = new Point(px, py).transform(invCtm);

			switch (event.getAction()) {
				case MotionEvent.ACTION_DOWN:
					currentStroke = new ArrayList<>();
					currentStroke.add(p);
					break;
				case MotionEvent.ACTION_MOVE:
					if (currentStroke != null)
						currentStroke.add(p);
					break;
				case MotionEvent.ACTION_UP:
					if (currentStroke != null) {
						currentStroke.add(p);
						inkList.add(currentStroke);
						redoList.clear();
						currentStroke = null;
					}
					break;
			}
			invalidate();
			return true;
		}

		if (!annotateMode) {
			switch (event.getAction()) {
				case MotionEvent.ACTION_MOVE:
					if (isSelecting) {
						float x = (event.getX() - getOffsetX()) * pageScale / viewScale;
						float y = (event.getY() - getOffsetY()) * pageScale / viewScale;
						selectionEnd = new Point(x, y);
						if (actionListener != null)
							actionListener.onSelectionChanged(selectionStart.x, selectionStart.y, selectionEnd.x, selectionEnd.y);
					}
					break;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					if (isSelecting) {
						isSelecting = false;
						if (actionListener != null)
							actionListener.onSelectionEnd();
					}
					break;
			}
		}

		detector.onTouchEvent(event);
		scaleDetector.onTouchEvent(event);
		return true;
	}

	public void setAnnotateMode(boolean mode) {
		annotateMode = mode;
		if (!annotateMode) {
			inkList.clear();
			redoList.clear();
		}
		invalidate();
	}

	public void setAnnotateColor(int color) {
		inkPaint.setColor(color);
	}

	public void undoAnnotate() {
		if (inkList.size() > 0) {
			redoList.add(inkList.remove(inkList.size() - 1));
			invalidate();
		}
	}

	public void redoAnnotate() {
		if (redoList.size() > 0) {
			inkList.add(redoList.remove(redoList.size() - 1));
			invalidate();
		}
	}

	public void setSelection(Quad[] quads) {
		selectedQuads = quads;
		invalidate();
	}

	public Point[][] getInkList() {
		if (inkList.isEmpty())
			return null;
		Point[][] list = new Point[inkList.size()][];
		for (int i = 0; i < inkList.size(); i++) {
			ArrayList<Point> stroke = inkList.get(i);
			list[i] = stroke.toArray(new Point[0]);
		}
		return list;
	}

	private int getOffsetX() {
		return (bitmapW <= canvasW) ? (canvasW - bitmapW) / 2 : -scrollX;
	}

	private int getOffsetY() {
		return (bitmapH <= canvasH) ? (canvasH - bitmapH) / 2 : -scrollY;
	}

	public boolean onDown(MotionEvent e) {
		scroller.forceFinished(true);
		return true;
	}

	public void onShowPress(MotionEvent e) { }

	public void onLongPress(MotionEvent e) {
		if (!annotateMode && actionListener != null) {
			float x = (e.getX() - getOffsetX()) * pageScale / viewScale;
			float y = (e.getY() - getOffsetY()) * pageScale / viewScale;

			if (actionListener.onAnnotationLongPress(x, y))
				return;

			isSelecting = true;
			selectedQuads = null;
			selectionStart = new Point(x, y);
			selectionEnd = new Point(x, y);
			actionListener.onSelectionChanged(x, y, x, y);
		}
	}

	public boolean onSingleTapUp(MotionEvent e) {
		if (annotateMode) return true;

		if (selectedQuads != null && actionListener != null) {
			actionListener.dismissSelection();
			return true;
		}

		boolean foundLink = false;
		float x = e.getX();
		float y = e.getY();
		if (showLinks && linkBounds != null) {
			float dx = (bitmapW <= canvasW) ? (bitmapW - canvasW) / 2 : scrollX;
			float dy = (bitmapH <= canvasH) ? (bitmapH - canvasH) / 2 : scrollY;
			float mx = (x + dx) / viewScale;
			float my = (y + dy) / viewScale;
			for (int i = 0; i < linkBounds.length; i++) {
				Rect b = linkBounds[i];
				if (mx >= b.x0 && mx <= b.x1 && my >= b.y0 && my <= b.y1) {
					if (Link.isExternal(linkURIs[i]) && actionListener != null)
						actionListener.gotoURI(linkURIs[i]);
					else if (actionListener != null)
						actionListener.gotoPage(linkURIs[i]);
					foundLink = true;
					break;
				}
			}
		}
		if (!foundLink) {
			float a = canvasW / 3;
			float b = a * 2;
			if (x <= a) goBackward();
			if (x >= b) goForward();
			if (x > a && x < b && actionListener != null) actionListener.toggleUI();
		}
		invalidate();
		return true;
	}

	public synchronized boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
		if (bitmap != null && !annotateMode) {
			if (isSelecting) {
				float x = (e2.getX() - getOffsetX()) * pageScale / viewScale;
				float y = (e2.getY() - getOffsetY()) * pageScale / viewScale;
				selectionEnd = new Point(x, y);
				if (actionListener != null)
					actionListener.onSelectionChanged(selectionStart.x, selectionStart.y, selectionEnd.x, selectionEnd.y);
			} else {
				scrollX += (int) dx;
				scrollY += (int) dy;
				scroller.forceFinished(true);
				invalidate();
			}
		}
		return true;
	}

	public synchronized boolean onFling(MotionEvent e1, MotionEvent e2, float dx, float dy) {
		if (bitmap != null && !annotateMode) {
			int maxX = bitmapW > canvasW ? bitmapW - canvasW : 0;
			int maxY = bitmapH > canvasH ? bitmapH - canvasH : 0;
			scroller.forceFinished(true);
			scroller.fling(scrollX, scrollY, (int)-dx, (int)-dy, 0, maxX, 0, maxY);
			invalidate();
		}
		return true;
	}

	public boolean onScaleBegin(ScaleGestureDetector det) {
		return !annotateMode;
	}

	public synchronized boolean onScale(ScaleGestureDetector det) {
		if (bitmap != null && !annotateMode) {
			float focusX = det.getFocusX();
			float focusY = det.getFocusY();
			float scaleFactor = det.getScaleFactor();
			float pageFocusX = (focusX + scrollX) / viewScale;
			float pageFocusY = (focusY + scrollY) / viewScale;
			viewScale *= scaleFactor;
			if (viewScale < minScale) viewScale = minScale;
			if (viewScale > maxScale) viewScale = maxScale;
			bitmapW = (int)(bitmap.getWidth() * viewScale / pageScale);
			bitmapH = (int)(bitmap.getHeight() * viewScale / pageScale);
			scrollX = (int)(pageFocusX * viewScale - focusX);
			scrollY = (int)(pageFocusY * viewScale - focusY);
			scroller.forceFinished(true);
			invalidate();
		}
		return true;
	}

	public void onScaleEnd(ScaleGestureDetector det) {
		if (actionListener != null && !annotateMode)
			actionListener.onPageViewZoomChanged(viewScale);
	}

	public void goBackward() {
		scroller.forceFinished(true);
		if (scrollY <= 0) {
			if (scrollX <= 0) {
				if (actionListener != null)
					actionListener.goBackward();
				return;
			}
			scroller.startScroll(scrollX, scrollY, -canvasW * 9 / 10, bitmapH - canvasH - scrollY, 500);
		} else {
			scroller.startScroll(scrollX, scrollY, 0, -canvasH * 9 / 10, 250);
		}
		invalidate();
	}

	public void goForward() {
		scroller.forceFinished(true);
		if (scrollY + canvasH >= bitmapH) {
			if (scrollX + canvasW >= bitmapW) {
				if (actionListener != null)
					actionListener.goForward();
				return;
			}
			scroller.startScroll(scrollX, scrollY, canvasW * 9 / 10, -scrollY, 500);
		} else {
			scroller.startScroll(scrollX, scrollY, 0, canvasH * 9 / 10, 250);
		}
		invalidate();
	}

	private android.graphics.Rect dst = new android.graphics.Rect();
	private Path path = new Path();

	private android.graphics.Matrix fitzToAndroid(Matrix m) {
		android.graphics.Matrix am = new android.graphics.Matrix();
		float[] values = new float[9];
		values[0] = m.a; values[1] = m.c; values[2] = m.e;
		values[3] = m.b; values[4] = m.d; values[5] = m.f;
		values[6] = 0;   values[7] = 0;   values[8] = 1;
		am.setValues(values);
		return am;
	}

	public void setTheme(int theme) {
		if (theme == 0) { // Light
			themePaint.setColorFilter(null);
			setBackgroundColor(0xFF505050);
		} else if (theme == 1) { // Grey
			float[] matrix = {
				-1, 0, 0, 0, 255, // red
				0, -1, 0, 0, 255, // green
				0, 0, -1, 0, 255, // blue
				0, 0, 0, 1, 0     // alpha
			};
			// Scale the inverted colors to a greyish tone
			float scale = 0.7f;
			for (int i = 0; i < 15; i++) matrix[i] *= scale;
			// Add some constant to avoid total black
			matrix[4] += 50;
			matrix[9] += 50;
			matrix[14] += 50;

			themePaint.setColorFilter(new ColorMatrixColorFilter(matrix));
			setBackgroundColor(0xFF333333);
		} else if (theme == 2) { // Black
			float[] matrix = {
				-1, 0, 0, 0, 255,
				0, -1, 0, 0, 255,
				0, 0, -1, 0, 255,
				0, 0, 0, 1, 0
			};
			themePaint.setColorFilter(new ColorMatrixColorFilter(matrix));
			setBackgroundColor(0xFF000000);
		}
		invalidate();
	}

	public synchronized void onDraw(Canvas canvas) {
		int x, y;

		if (bitmap == null) {
			if (error) {
				canvas.translate(canvasW / 2, canvasH / 2);
				canvas.drawPath(errorPath, errorPaint);
			}
			return;
		}

		if (scroller.computeScrollOffset()) {
			scrollX = scroller.getCurrX();
			scrollY = scroller.getCurrY();
			invalidate(); /* keep animating */
		}

		x = getOffsetX();
		y = getOffsetY();

		dst.set(x, y, x + bitmapW, y + bitmapH);
		canvas.drawBitmap(bitmap, null, dst, themePaint);

		if (showLinks && linkBounds != null) {
			for (Rect b : linkBounds) {
				canvas.drawRect(
					x + b.x0 * viewScale,
					y + b.y0 * viewScale,
					x + b.x1 * viewScale,
					y + b.y1 * viewScale,
					linkPaint
				);
			}
		}

		if (hits != null && hits.length > 0) {
			for (Quad[] h : hits)
				for (Quad q : h) {
					path.rewind();
					path.moveTo(x + q.ul_x * viewScale / pageScale, y + q.ul_y * viewScale / pageScale);
					path.lineTo(x + q.ll_x * viewScale / pageScale, y + q.ll_y * viewScale / pageScale);
					path.lineTo(x + q.lr_x * viewScale / pageScale, y + q.lr_y * viewScale / pageScale);
					path.lineTo(x + q.ur_x * viewScale / pageScale, y + q.ur_y * viewScale / pageScale);
					path.close();
					canvas.drawPath(path, hitPaint);
				}
		}

		if (selectedQuads != null) {
			for (Quad q : selectedQuads) {
				path.rewind();
				path.moveTo(x + q.ul_x * viewScale / pageScale, y + q.ul_y * viewScale / pageScale);
				path.lineTo(x + q.ll_x * viewScale / pageScale, y + q.ll_y * viewScale / pageScale);
				path.lineTo(x + q.lr_x * viewScale / pageScale, y + q.lr_y * viewScale / pageScale);
				path.lineTo(x + q.ur_x * viewScale / pageScale, y + q.ur_y * viewScale / pageScale);
				path.close();
				canvas.drawPath(path, selectionPaint);
			}
		}

		if (annotateMode && ctm != null) {
			canvas.save();
			canvas.translate(x, y);
			canvas.scale(viewScale, viewScale);
			canvas.concat(fitzToAndroid(ctm));
			for (ArrayList<Point> stroke : inkList) {
				if (stroke.size() > 1) {
					tempPath.rewind();
					Point p0 = stroke.get(0);
					tempPath.moveTo(p0.x, p0.y);
					for (int i = 1; i < stroke.size(); i++) {
						Point pi = stroke.get(i);
						tempPath.lineTo(pi.x, pi.y);
					}
					canvas.drawPath(tempPath, inkPaint);
				}
			}
			if (currentStroke != null && currentStroke.size() > 1) {
				tempPath.rewind();
				Point p0 = currentStroke.get(0);
				tempPath.moveTo(p0.x, p0.y);
				for (int i = 1; i < currentStroke.size(); i++) {
					Point pi = currentStroke.get(i);
					tempPath.lineTo(pi.x, pi.y);
				}
				canvas.drawPath(tempPath, inkPaint);
			}
			canvas.restore();
		}
	}
}
