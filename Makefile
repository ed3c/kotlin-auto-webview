.PHONY: test desktop web android ios check paths paths-selftest snapshots snapshots-selftest sources sources-selftest

test:
	./gradlew :composeApp:allTests

# Cheap enough to run before pushing; catches what a case-sensitive CI runner cannot see.
paths:
	scripts/ci/check-path-collisions.sh

paths-selftest:
	scripts/ci/check-path-collisions.sh --selftest

# Also runs automatically as a dependency of the SQLDelight generator.
snapshots:
	python3 scripts/ci/check-schema-snapshots.py

snapshots-selftest:
	python3 scripts/ci/check-schema-snapshots.py --selftest

# A NUL byte turns a source file binary to git; nothing else notices.
sources:
	python3 scripts/ci/check-text-sources.py

sources-selftest:
	python3 scripts/ci/check-text-sources.py --selftest

desktop:
	./gradlew :composeApp:run

web:
	./gradlew :composeApp:wasmJsBrowserDevelopmentRun

android:
	./gradlew :composeApp:assembleDebug

ios:
	./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

check: paths sources snapshots test
	./gradlew :composeApp:compileKotlinDesktop :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug
