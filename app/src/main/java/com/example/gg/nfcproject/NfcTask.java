package com.example.gg.nfcproject;

import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.support.v7.app.AppCompatActivity;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Created by xiaolou on 2018/1/29.
 */

public class NfcTask extends AsyncTask<IsoDep, String, String[]> {
    private final WeakReference<TextView> mTextViewRefrence;
    private final WeakReference<ProgressBar> mProgressBar;
    private static final String TAG = "NfcTask";

    public NfcTask(AppCompatActivity appCompatActivity) {
        TextView textView = (TextView) appCompatActivity.findViewById(R.id.text_view);
        textView.setMovementMethod(new ScrollingMovementMethod());
        mTextViewRefrence = new WeakReference<>(textView);

        ProgressBar progressBar = (ProgressBar) appCompatActivity.findViewById(R.id.process_bar);
        mProgressBar = new WeakReference<>(progressBar);
    }

    @Override
    protected void onPreExecute() {
        TextView textView = mTextViewRefrence.get();
        if (textView != null) {
            textView.setText("开始读取卡片...");
        }

        ProgressBar progressBar = mProgressBar.get();
        if (progressBar != null) {
            progressBar.setVisibility(ProgressBar.VISIBLE);
        }
    }

    @Override
    protected String[] doInBackground(IsoDep... isoDeps) {
        IsoDep isoDep = isoDeps[0];

        try {
            isoDep.connect();

            PBOCUtil pboc = new PBOCUtil(isoDep);
            if (pboc.pbocAppSelect() < 0) {
                return null;
            }

            pboc.pbocInsertTLV("9F66", "40000000");
            pboc.pbocInsertTLV("9F02", "000000000001");
            pboc.pbocInsertTLV("9F03", "000000000000");
            pboc.pbocInsertTLV("9F1A", "0156");
            pboc.pbocInsertTLV("95", "0000000000");
            pboc.pbocInsertTLV("5F2A", "0156");
            pboc.pbocInsertTLV("9A", "170831");
            pboc.pbocInsertTLV("9C", "04");
            pboc.pbocInsertTLV("9F37", "01234567");
            pboc.pbocInsertTLV("9F21", "080000");
            pboc.pbocInsertTLV("9F4E", "1414141414141414141414141414141414141414");

            publishProgress("卡片应用初始化...");
            if (pboc.pbocAppInit() < 0) {
                publishProgress("卡片应用初始化失败");
                return null;
            }

            publishProgress("终端行为分析...");
            if (pboc.pbocTermActAnalyze() < 0) {
                publishProgress("终端行为分析失败");
                return null;
            }

            publishProgress("获取交易明细...");
            String[] transDetail = pboc.pbocGetTransDetail();
            if (transDetail == null) {
                publishProgress("获取交易明细失败");
            }
            return transDetail;

        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    protected void onPostExecute(String... transDetailStrings) {
        ProgressBar progressBar = mProgressBar.get();
        if (progressBar != null) {
            progressBar.setVisibility(ProgressBar.GONE);
        }

        TextView textView = mTextViewRefrence.get();
        if (textView == null) {
            Log.d(TAG, "Activity is destroyed");
            return;
        }

        if (transDetailStrings == null) {
            textView.setText("读取卡片失败");
            return;
        }

        textView.setText("交易明细：\n");
        for (String string: transDetailStrings) {
            if (string != null) {
                textView.append(string + "\n\n");
            }
        }
    }

    @Override
    protected void onProgressUpdate(String... updateString) {
        TextView textView = mTextViewRefrence.get();
        if (textView == null) {
            Log.d(TAG, "Activity is destroyed");
            return;
        }

        if (updateString == null) {
            Log.d(TAG, "updateString should not be null");
            return;
        }

        for (String tmpString: updateString) {
            textView.setText(tmpString);
        }
    }
}
