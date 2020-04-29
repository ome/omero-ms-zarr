#!/usr/bin/env python

import dask.array as da
import argparse
import time


def timeit(method):
    def timed(*args, **kw):
        ts = time.time()
        result = method(*args, **kw)
        te = time.time()
        if 'log_time' in kw:
            name = kw.get('log_name', method.__name__.upper())
            kw['log_time'][name] = int((te - ts) * 1000)
        else:
            print('%r  %2.2f ms' %
                  (method.__name__, (te - ts) * 1000))
        return result
    return timed


@timeit
def load(source_array):
    return da.from_zarr(source_array)


@timeit
def resize(arr, chunks):
    return arr.rechunk(chunks)


@timeit
def convert(resized, target_array):
    da.to_zarr(resized, target_array)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dimensions", type=int, default="5")
    parser.add_argument("source_array")
    parser.add_argument("target_array")
    parser.add_argument("chunks")
    args = parser.parse_args()

    chunks = [int(x) for x in args.chunks.split(",")]
    assert len(chunks) == args.dimensions

    convert(
        resize(
            load(args.source_array),
            chunks
        ),
        args.target_array
    )


if __name__ == "__main__":
    main()
