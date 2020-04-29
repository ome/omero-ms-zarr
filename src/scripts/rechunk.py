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
    parser.add_argument("--dimensions", type=int, default="5", metavar="DIMS",
                        help="number of chunks to parse (%(default)s)")
    parser.add_argument("--distributed", action="store_true",
                        help="enable distributed dashboard (%(default)s)")
    parser.add_argument("source_array",
                        help="array to copy from. must exist.")
    parser.add_argument("target_array",
                        help="array to write to. may not exist.")
    parser.add_argument("chunks", default="1024,1024,1,1,1",
                        help=("comma-separated string of chunk sizes "
                              "(%(default)s)"))
    args = parser.parse_args()

    if args.distributed:
        from dask.distributed import Client
        client = Client()
        input("Visit http://localhost:8787/status - Press Enter to continue...")

    chunks = [int(x) for x in args.chunks.split(",")]
    assert len(chunks) == args.dimensions

    convert(
        resize(
            load(args.source_array),
            chunks
        ),
        args.target_array
    )

    if args.distributed:
        input("Press Enter to exit...")


if __name__ == "__main__":
    main()
