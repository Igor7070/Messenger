package com.example.unlimitedpossibilities12.restApi;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface ResumeApi {
    @POST("/api/convenient_job_search/readyResume")
    Call<ResponseBody> createResume(@Body ResumeRequest request);
}
