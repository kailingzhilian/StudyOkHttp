package com.kai.ling.myapplication;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import java.io.IOException;
import java.util.logging.Logger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private Object data;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    public String executeGetData(String url) {
        OkHttpClient okHttpClient = new OkHttpClient.Builder().build();

        Request request = new Request.Builder()
                .url(url)
                .build();

        Response response = null;
        try {
            response = okHttpClient.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    public void enqueueGetData(String url) {
        OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
        Request request = new Request.Builder()
                .url(url)
                .build();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                //TODO 失败的回调函数
                Log.i("enqueueGetData","失败的回调函数");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                //TODO 成功的回调函数
                Log.i("enqueueGetData","成功的回调函数");
            }
        });

    }
}
