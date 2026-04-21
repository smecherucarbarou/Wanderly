# Project Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove verified dead files and align the live code with a clearer feature/common package structure without changing runtime behavior.

**Architecture:** Keep all behavior intact and limit the cleanup to validated dead resources plus package/file moves for already-related UI classes. Prefer grouping by feature (`auth`, `missions`, `social`) and shared UI concerns (`common`) so future work lands in predictable places.

**Tech Stack:** Kotlin, AndroidX Fragments/ViewModels, Navigation Component, Glide, Gradle Kotlin DSL

---

### Task 1: Remove verified dead files

**Files:**
- Delete: `app/src/main/res/drawable/ic_launcher_foreground12112.xml`
- Delete: `app/src/res/drawable/ic_launcher_background.xml`

- [ ] Confirm each file has no live references in source or resources.
- [ ] Delete only the files that are outside active source sets or have zero references.
- [ ] Run a focused verification pass so resource packaging still succeeds.

### Task 2: Group shared UI helpers

**Files:**
- Move: `app/src/main/java/com/novahorizon/wanderly/Utils.kt`
- Move: `app/src/main/java/com/novahorizon/wanderly/ui/AvatarLoader.kt`
- Move: `app/src/main/java/com/novahorizon/wanderly/ui/WanderlyViewModelFactory.kt`

- [ ] Move shared UI helpers into `app/src/main/java/com/novahorizon/wanderly/ui/common/`.
- [ ] Rename `Utils.kt` to a clearer file name matching its snackbar responsibility.
- [ ] Update every affected import.

### Task 3: Finish feature package cleanup

**Files:**
- Move: `app/src/main/java/com/novahorizon/wanderly/ui/AuthViewModel.kt`
- Move: `app/src/main/java/com/novahorizon/wanderly/ui/MainViewModel.kt`
- Move: `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt`
- Move: `app/src/main/java/com/novahorizon/wanderly/ui/SocialFragment.kt`
- Move: `app/src/main/java/com/novahorizon/wanderly/ui/SocialViewModel.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/MainActivity.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/ui/auth/LoginFragment.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/ui/auth/SignupFragment.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/ui/gems/GemsFragment.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/ui/map/MapFragment.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/ui/profile/DevDashboardFragment.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt`
- Modify: `app/src/main/res/navigation/nav_graph.xml`

- [ ] Move viewmodels/fragments into their matching feature packages.
- [ ] Update package declarations and imports in one pass.
- [ ] Re-run verification and fix any fallout from the package moves.
