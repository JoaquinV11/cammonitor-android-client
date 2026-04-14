package com.example.camara

// Optional public-IP refresh endpoint (disabled by default).
const val ENABLE_GITHUB_PUBLIC_IP_REFRESH = false

// Simple cache for last good public IP
const val PREFS_NET = "net_cache"
const val KEY_LAST_PUBLIC_IP = "last_public_ip"

const val GH_PUBLIC_IP_URL =
    "" // Optional GitHub Contents API URL for public-ip.json

const val REC_LAN_HOST = "backend.local" // fallback only
const val REC_LAN_PORT = 8080
const val REC_HEALTH_URL_FALLBACK = "http://backend.local:8080/api/health"

// ---------- Tunables for fast detection ----------
const val FAST_CONNECT_MS_1 = 350
const val FAST_CONNECT_MS_2 = 700
const val OVERALL_CAP_MS_WIFI = 1200L
const val OVERALL_CAP_MS_CELL = 900L
