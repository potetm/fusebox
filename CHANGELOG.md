# Changelog
## 1.0.10
### Enhancements
* The exception thrown after retries are exhausted may now be controlled via
  the `::retry/exception` option. See docstring for `retry/init` for details.
  Thanks to @mk for reporting!
* Fusebox now runs on Babashka (except for timeouts. Pending a [sci patch](https://github.com/babashka/sci/issues/959)
  to make timeouts work properly). Thanks to @borkdude for his help!

## 1.0.9
### Enhancements
* Retry exceptions now include the last failing value under `::retry/val`. See
  docstring for `retry/init` for details.
* Exceptions are now less verbose.
    * Exceptions no longer include `::fb/spec`. This key was not documented and
      was considered alpha. Including the spec in the exception causes
      printed exceptions to be massive—especially if you merge many specs
      together, which is a target use case for Fusebox. After using it for a time,
      it was decided that `::fb/spec` is overly verbose and doesn't add much value.
    * Relevant keys for a given spec can be found at the top level of
      `ex-data`. For example, an exception thrown by Timeout will include
      a `::to/timeout-ms` key.
* Memoize is now less verbose.
    * Including `ConcurrentHashMap` under an undocumented implementation key
    caused it to get printed when `memo` is printed. Memoized values may be
    sensitive, and therefore `memo` should be opaque. This change closes over
    `ConcurrentHashMap`, preventing it from being printed.
* Document the data included in thrown ex-infos.

## 1.0.8
### Enhancements
* Use a shared thread for rate limiting instead of spawning a thread per rate
  limit instance.
