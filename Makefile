.PHONY: test desktop web android ios check paths paths-selftest

test:
	./gradlew :composeApp:allTests

# Cheap enough to run before pushing; catches what a case-sensitive CI runner cannot see.
paths:
	scripts/ci/check-path-collisions.sh

paths-selftest:
	scripts/ci/check-path-collisions.sh --selftest

desktop:
	./gradlew :composeApp:run

web:
	./gradlew :composeApp:wasmJsBrowserDevelopmentRun

android:
	./gradlew :composeApp:assembleDebug

ios:
	./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

check: paths test
	./gradlew :composeApp:compileKotlinDesktop :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug
