run: dir.zarr
	cd dir.zarr && python -m http.server

dir.zarr:
	./create-zarr.py

test:
	./explore-zarr.py

.PHONY: run test
