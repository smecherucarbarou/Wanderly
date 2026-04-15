# Wanderly - Project Context & Architecture

## 1. Project Overview
**Wanderly** is a high-end travel and lifestyle Android application designed to discover "hidden gems" (cafes, bars, viewpoints, galleries) using AI-driven curation and real-world metadata verification. It aims to eliminate "tourist traps" and irrelevant results (government offices, clinics, etc.) by acting as an elite Lifestyle Scout.

## 2. Core Tech Stack
- **Language:** Kotlin
- **Platform:** Android (Min SDK: 24, Target SDK: 34)
- **AI:** Google Gemini AI (via `GeminiClient.kt`)
- **Backend:** Supabase (Auth & Database)
- **Maps:** osmdroid (OpenStreetMap) + Google Play Services Location
- **Places Data:** Google Places API (New) for verification
- **Serialization:** Kotlinx Serialization

## 3. Detailed Component Analysis (by Path)

### API Layer (`app/src/main/java/com/novahorizon/wanderly/api/`)
- **`GeminiClient.kt`**: Handles communication with Google's Generative AI. Configures model parameters (temperature, topK, topP) and manages the execution of curation prompts.
- **`PlacesGeocoder.kt`**: 
    - **Bullshit Filter**: The most critical security layer. Validates AI-suggested names against Google's real-time database.
    - Rejects `CLOSED` status and excludes specific `primaryType` tags (government, medical, bank, lodging, etc.).
    - Extracts `editorialSummary`, `rating`, and exact coordinates.
- **`SupabaseClient.kt`**: Initializes the Supabase client for authentication and database interactions (PostgreSQL/PostgREST).

### UI Layer (`app/src/main/java/com/novahorizon/wanderly/ui/`)
- **`gems/GemsFragment.kt`**: 
    - **Prompt Engineering**: Houses the "Elite Lifestyle Scout" prompt.
    - **Flow**: Fetches "local hints" from OSM -> Calls Gemini -> Validates each result via `PlacesGeocoder` -> Displays verified list.
- **`map/MapFragment.kt`**: 
    - Integrates **osmdroid**.
    - Displays user location and place markers.
    - Handles map interactions and centering.
- **`auth/LoginFragment.kt` & `RegisterFragment.kt`**: Manage user lifecycle via Supabase Auth.
- **`profile/ProfileFragment.kt`**: Displays user statistics and saved "gems" or history.

### Data Layer (`app/src/main/java/com/novahorizon/wanderly/data/`)
- **`Gem.kt`**: The standard data structure for a verified location. Includes name, description, lat/lng, and a "reason" (AI-generated "wow" factor).
- **`WanderlyRepository.kt`**: 
    - Uses the **Overpass API** to fetch nearby points of interest (POIs) from OpenStreetMap.
    - These POIs are used as "contextual anchors" to help Gemini suggest relevant nearby spots.
- **`Profile.kt`, `Mission.kt`, `Friendship.kt`**: Define the social and gamification structures of the app.

## 4. Operational Logic & Rules

### A. The "Bullshit Filter" Rules
Locations are **REJECTED** if:
1. `businessStatus` is "CLOSED_PERMANENTLY" or "CLOSED_TEMPORARILY".
2. `primaryType` or `types` contains: `government_office`, `social_service_organization`, `police`, `hospital`, `dentist`, `bank`, `atm`, `lodging`, `school`.
3. The location is more than **10km** away from the user.

### B. AI Curation (The "High-Vibe" Prompt)
- **Persona**: Elite, world-class Lifestyle Scout.
- **Focus**: Specialty Coffee, Aesthetic Cafes, Cocktail Bars, Fine Dining, Secret Rooftops, Independent Galleries.
- **Constraint**: Must verify that venues with "Social" in the name are commercial bars/lounges, not government departments.

## 5. Setup & Security
- **API Keys**: Stored in `local.properties` and accessed via `BuildConfig`.
- **Permissions**: Requires `INTERNET`, `ACCESS_FINE_LOCATION`, and `ACCESS_COARSE_LOCATION`.
- **Auth**: Secured via Supabase JWT.

## 6. Directory Structure Summary
```text
app/src/main/java/com/novahorizon/wanderly/
├── api/          # Network & AI (Gemini, Google Places, Supabase)
├── ui/           # Fragments & Adapters (Gems, Map, Auth, Profile)
├── data/         # Data Models & Repositories (Overpass/OSM logic)
└── MainActivity  # Main entry point and NavHost configuration
```
