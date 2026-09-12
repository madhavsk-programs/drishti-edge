package com.drishti.app.net

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** DRISHTI local backend, contract version 1.0.0. All paths under /api/v1. */
interface DrishtiApi {

    @GET("api/v1/health")
    suspend fun health(): Response<HealthResponse>

    @POST("api/v1/walk/sessions")
    suspend fun startSession(@Body request: StartWalkSessionRequest): Response<StartWalkSessionResponse>

    @PATCH("api/v1/walk/sessions/{id}/end")
    suspend fun endSession(@Path("id") sessionId: String): Response<EndWalkSessionResponse>

    // Detection, OCR, scene description and target locating all run on the
    // phone. The coordinator only backs the dashboard, so this client no
    // longer has /walk/analyze, /explore or /vlm/* — see BUILD_PLAN.md A9.

    @POST("api/v1/hazards")
    suspend fun createHazard(@Body request: CreateHazardRequest): Response<HazardResponse>

    @Multipart
    @POST("api/v1/hazards")
    suspend fun createHazardWithEvidence(
        @Part("payload") payload: RequestBody,
        @Part evidence: MultipartBody.Part,
    ): Response<HazardResponse>

    @GET("api/v1/hazards/nearby")
    suspend fun nearbyHazards(
        @Query("map_id") mapId: String,
        @Query("map_version") mapVersion: String,
        @Query("map_x") mapX: Double,
        @Query("map_y") mapY: Double,
        @Query("radius") radius: Double,
    ): Response<HazardListResponse>
}
