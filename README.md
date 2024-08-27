# Fusebox
An extremely lightweight [fault tolerance library](#what-is-a-fault-tolerance-library) for Clojure(Script)

## Current Release
```clj
com.potetm/fusebox {:mvn/version "1.0.7"}
```

## Rationale
Fault tolerance libraries—both in Java and in Clojure—are heavyweight, have
dozens of options, are callback-driven, and have extremely complicated
execution models. Javascript appears to have one popular option, but it too is
option heavy, and it's missing many of the features one would expect of a
fully-fledged fault tolerance library.

Clojure is a simple language. We deserve a simple resilience library.

Fusebox was designed to have the following properties:

* [Fast](./docs/benchmarks.md)
* Prefer pure functions to additional options
* Modular (load only what you need)
* Sequential execution (no callbacks)
* Use simple, un-nested hashmaps with namespaced keys
* One dependency: [clojure/tools.logging](https://github.com/clojure/tools.logging)
* Support a variety of usage patterns

Lastly, my hope is that you will look at some of the code and realize how
straightforward it is. It's almost laughable. These utilities are identical to
Resilience4J at their core, but thanks to immutable data, namespaced keys, and a
dash of macros, Clojure affords us _much_ simpler implementations.

## Usage
### Table of Contents
* [What is a Fault Tolerance Library?](#what-is-a-fault-tolerance-library)
* [Bulkhead](#bulkhead)
* [Circuit Breaker](#circuit-breaker)
* [Fallback](#fallback)
* [Memoize](#memoize)
* [Rate Limit](#rate-limit)
* [Retry](#retry)
* [Timeout](#timeout)
* [Register](#register)
* [Bulwark](#bulwark)
* [Usage Notes](#usage-notes)
  * [Pass-through Invocations](#pass-through-invocations)
  * [`init` and `shutdown` Functions](#init-and-shutdown-functions)
  * [Disabling](#disabling)
  * [spec maps](#spec-maps)
  * [Overriding Values](#overriding-values)
  * [Exceptions](#exceptions)
  * [Why `tool.logging`?](#why-toolslogging)
  * [Clojurescript](#clojurescript)
* [Benchmarks](./docs/benchmarks.md)

### What is a Fault Tolerance Library?
A fault tolerance library is a collection of utilities designed to keep your system
running in the face of latency and errors. Those utilities help keep your application
up and running, _and_ they help ensure that your application doesn't overwhelm another
part of the system.

If your application makes or receives network calls, you probably want to be using a
fault tolerance library.

The most in-depth treatment for fault tolerance is [_Release It!_](https://www.amazon.com/dp/1680502395/)
by Michael Nygard. It is the only book that I would consider mandatory for software
engineers. I highly recommend you read it.

That said, here is a short motivator for each utility:

* [Bulkhead](#bulkhead): Limits the number of threads that can make a certain call or group of
  calls. It ensures your application doesn't lock up when another application is
  slow.
* [Circuit Breaker](#circuit-breaker): Detects when another application is slow or failing, and stops
  your application from making it worse by continuing to call it.
* [Fallback](#fallback): Provides your application a value to use while a failing application
  is unavailable.
* [Memoize](#memoize): A rudimentary cache you can use to reduce load on other applications.
  (If you're considering using this, you should see if a true [cache](https://github.com/ben-manes/caffeine)
  makes more sense.)
* [Rate Limit](#rate-limit): Limits the rate at which your application calls another application.
  Many times rate limits are part of an application's SLA, and using a rate limiter
  lets you easily abide by the SLA.
* [Retry](#retry): Retries a call in the event of failure.
* [Timeout](#timeout): Sets a self-imposed time limit for a response from another
  application. Ensures your application remains responsive when another application
  is slow.

### Bulkhead
```clj
(require '[com.potetm.fusebox.bulkhead :as bh])

(def bulkhead
  (bh/init {::bh/concurrency 2
            ::bh/wait-timeout-ms 100}))

(bh/with-bulkhead bulkhead
  (run))
```

* `::bh/concurrency` - the integer number of concurrent callers to allow
* `::bh/wait-timeout-ms` - max millis a thread will wait to enter bulkhead

### Circuit Breaker
```clj
(require '[com.potetm.fusebox.circuit-breaker :as cb])

(def circuit-breaker
  (cb/init {::cb/next-state #(cb/next-state:default {:fail-pct 0.5
                                                     :slow-pct 0.5
                                                     :wait-for-count 100
                                                     :open->half-open-after-ms 100}
                                                     %)
            ::cb/hist-size 10
            ::cb/half-open-tries 3
            ::cb/slow-call-ms 100}))

(cb/with-circuit-breaker circuit-breaker
  (run))
```

* `::cb/next-state` - fn taking the current `Record` record and returning the next
                      state or nil if no transition is necessary. See `cb/next-state:default`
                      for a default implementation. Return value must be one of:
                      `::cb/closed`, `::cb/half-opened`, `::cb/opened`
* `::cb/hist-size` - The number of calls to track
* `::cb/half-open-tries` - The number of calls to allow in a `::cb/half-opened` state
* `::cb/slow-call-ms` - Millisecond threshold to label a call slow
* `::cb/success?` - (Optional) A function which takes a return value and determines
                    whether it was successful. If false, a `::cb/failure` is
                    recorded. Defaults to `(constantly true)`.

`::cb/next-state` will be run on every invocation, so it must be fast.
`cb/next-state:default` should work for the vast majority of use cases. Using it
as a guide, it's straightforward enough to implement a custom `::cb/next-state`
function. There are a variety of helpers in `com.potetm.fusebox.circuit-breaker`
to help you.

`cb/next-state:default` takes the following parameters in the first argument:

* `:fail-pct` - The decimal threshold to use to open the breaker due to failed calls (0, 1]
* `:slow-pct` - The decimal threshold to use to open the breaker due to slow calls (0, 1]
* `:wait-for-count` - The number of calls to wait for after transitioning before transitioning again
* `:open->half-open-after-ms` - Millis to wait before transitioning from `::opened` to `::half-opened`

### Fallback
```clj
(require '[com.potetm.fusebox.fallback :as fallback])

(def fallback
  (fallback/init {::fallback/fallback (fn [ex]
                                        123)}))

(fallback/with-fallback fallback
  (run))
```

* `::fallback/fallback` - fn to invoke upon exception. Takes one arg, the exception that was thrown.
                          The return value of fn is returned to the caller.

### Memoize
```clj
(require '[com.potetm.fusebox.memoize :as memo])

(def memo (memo/init {::memo/fn expensive-fn}))

(memo/get memo
          args
          to
          expensive-fn)
```

* `::memo/fn` - The function to memoize. Guaranteed to only be called once.

Most production applications will want to use a cache instead of memoize. It's included
in this library for three reasons:

1. Memoize makes sense for a small subset of use cases.
2. `clojure.core/memoize` will re-run its fn under contention, and you probably want to avoid it.
3. To show a good template for setting up a [cache](https://github.com/ben-manes/caffeine).

### Rate Limit
```clj
(require '[com.potetm.fusebox.rate-limit :as rl])

(def rate-limit
  (rl/init {::rl/bucket-size 10
            ::rl/period-ms 1000
            ::rl/wait-timeout-ms 5000}))

(rl/with-rate-limit rate-limit
  (run))
```

* `::rl/bucket-size` - the integer number of tokens per period
* `::rl/period-ms` - millis in each period
* `::rl/wait-timeout-ms` - max millis a thread waits for a token

Fusebox's rate limiter is a [Token Bucket](https://en.wikipedia.org/wiki/Token_bucket)
rate limiter. You can easily turn it into a [Leaky Bucket](https://en.wikipedia.org/wiki/Leaky_bucket)
by setting the `::rl/bucket-size` to 1 and adjusting `::rl/period-ms` appropriately.

For example the following spec turns the above rate limiter into a leaky bucket:

```clj
{::rl/bucket-size 1
 ::rl/period-ms 100
 ::rl/wait-timeout-ms 5000}
```

### Retry
```clj
(require '[com.potetm.fusebox.retry :as retry])

(def retry
  (retry/init {::retry/retry? (fn [n ms ex]
                                (< n 10))
               ::retry/delay (fn [n ms ex]
                               (min (retry/delay-exp n)
                                    5000))}))

(retry/with-retry retry
  (run))
```

* `::retry/retry?` - A predicate called after an exception to determine whether
                     body should be retried. Takes three args:
  * eval-count
  * exec-duration-ms
  * the exception/failing value
* `::retry/delay` - A function which calculates the delay in millis to
                    wait prior to the next evaluation. Takes three args:
  * eval-count
  * exec-duration-ms
  * the exception/failing value
* `::success?` - (Optional) A function which takes a return value and determines
                 whether it was successful. If false, body is retried.
                 Defaults to `(constantly true)`.

There are a few functions in `com.potetm.fusebox.retry` that will help you write
a `::retry/delay` fn:

* `delay-exp` - An exponential delay
* `delay-linear` - A linear delay
* `jitter` - Add a random jitter to a base delay, e.g. `(jitter 0.10 (delay-linear 100 count))`

You probably want your `::retry/delay` fn to cap the delay with a call to `min`
like so:

```
(jitter 0.10
        (min (delay-exp 100 count)
             10000))
```

To aid in diagnostic feedback, you can optionally insert bindings for:

* `retry-count` - number of retries attempted (starts at zero)
* `exec-duration-ms` - total execution duration in millis

These bindings are the first arguments to `with-retry`. For example:

```clj
(retry/with-retry [retry-count exec-duration-ms] retry
  (when (and retry-count (pos? retry-count))
    (log/warn "Retrying!"
              {:retry-count retry-count}))
  (something-that-needs-retries))
```

NOTE: If you choose to use these bindings, it's advised that you nil-guard your
usage in order to preserve [pass-through invocations](#pass-through-invocations).

Of course, feel free to macro/wrap to taste.

### Timeout
```clj
(require '[com.potetm.fusebox.timeout :as to])

(def timeout
  (to/init {::to/timeout-ms 5}))

(to/with-timeout timeout
  (run))
```

* `::timeout-ms` - millis to wait before timing out
* `::interrupt?` - bool indicating whether a timed-out thread should be interrupted
  on timeout (Defaults to `true`).

The timeout namespace also includes a macro `try-interruptible` that you should
prefer instead of traditional `try` when using `with-timeout`. It guarantees that
`InterruptedException` is rethrown instead of swallowed, which is the only way to
stop a thread on the JVM.

### Register
```clj
(require '[com.potetm.fusebox.retry :as retry]
         '[com.potetm.fusebox.registry :as reg])

(reg/register! ::github
               (retry/init {::retry/retry? (fn [n ms ex]
                                             (< n 10))
                            ::retry/delay (fn [n ms ex]
                                            (min (retry/delay-exp n)
                                                 5000))}))
(retry/with-retry (reg/get ::github)
  (run))
```

Registry is included for the following reasons:

1. Many resilience libraries use registries, so people are used to it.
2. It's a fine way to organize Fusebox specs.
3. It was easy to do.

That said, you shouldn't feel compelled to use it where a `def` or argument passing
would suffice.

### Bulwark
```clj
(require '[com.potetm.fusebox.bulkhead :as bh]
         '[com.potetm.fusebox.bulwark :as bw]
         '[com.potetm.fusebox.circuit-breaker :as cb]
         '[com.potetm.fusebox.fallback :as fallback]
         '[com.potetm.fusebox.rate-limit :as rl]
         '[com.potetm.fusebox.retry :as retry]
         '[com.potetm.fusebox.timeout :as to])

(def spec
  (merge (retry/init {::retry/retry? (fn [c dur ex]
                                       (< c 10))
                      ::retry/delay (constantly 10)})
         (to/init {::to/timeout-ms 500})
         (fallback/init {::fallback/fallback (fn [ex]
                                               :yes!)})
         (cb/init {::cb/next-state (partial cb/next-state:default
                                            {:fail-pct 0.5
                                             :slow-pct 0.5
                                             :wait-for-count 3
                                             :open->half-open-after-ms 100})
                   ::cb/hist-size 10
                   ::cb/half-open-tries 3
                   ::cb/slow-call-ms 100})
         (rl/init {::rl/bucket-size 10
                   ::rl/period-ms 1000
                   ::rl/wait-timeout-ms 100})
         (bh/init {::bh/concurrency 5
                   ::bh/wait-timeout-ms 100})))

(bw/bulwark spec
  (run))
```

Bulwark is nothing more than a default ordering of utilities:

```clj
(defmacro bulwark [spec & body]
  `(fallback/with-fallback ~spec
     (retry/with-retry ~spec
       (cb/with-circuit-breaker ~spec
         (bh/with-bulkhead ~spec
           (rl/with-rate-limit ~spec
             (to/with-timeout ~spec
               ~@body)))))))
```

Due to [pass-through invocations](#pass-through-invocations), you can use this
ordering for any combination of utilities.

## Usage Notes
### Pass-through Invocations
Every utility  is designed to take hashmaps that _don't_ include the keys that
it needs. `nil` is supported as well. In those cases, calling the utility is a
pass-through. The provided body is executed as-is.

This allows you to set up general-purpose functions that properly order your
resilience utilities and allow individual code paths to opt-in to the functionality
they need.

For example, your http client may be wrapped like so:


```clj
(defn http [req]
  (retry/with-retry req
    (rl/with-rate-limit req
      (http/invoke req))))
```

And then be invoked in the following ways:

```clj
(def retry
  (retry/init {::retry/retry? (fn [n ms ex]
                                (< n 10))
               ::retry/delay (fn [n ms ex]
                               (min (retry/delay-exp n)
                                    5000))}))

(def rate-limit
  (rl/init {::rl/bucket-size 10
            ::rl/period-ms 1000
            ::rl/wait-timeout-ms 5000}))

;; Only retry
(http (merge req retry))

;; Only rate limit
(http (merge req rate-limit))

;; Retry AND rate limit
(http (merge req retry rate-limit))
```

### `init` and `shutdown` functions
Every namespace has an `init` and `shutdown` fn—even when initialization and
shutdown aren't required (e.g. for [Retry](#retry), which is a map of pure functions).
This is for two reasons:

1. To provide validation on startup
2. To provide a uniform interface

For this reason, you should always call `init` and `shutdown`—especially if you're
just getting started with Fusebox.


### Disabling
Every namespace has a `disable` function that you can use to disable that utility
for a specific invocation. NOTE: It only disables the utility for _that_ invocation.
It does not disable the utility for future invocations or across threads.

This is most useful at the REPL. For example, you might be testing a failing call,
and you don't want to wait for the retries to complete. However, you should feel
free to use it in production if you find a use case for it.

### spec maps
Every `init` merges in the data it needs. It will not alter other keys in input
map, so you should feel free to pass extra keys if you see fit:

```clj
(retry/init {:headers {"authorization" "SUPER_SECRET"}
             ::retry/retry? (fn [n ms ex]
                              (< n 10))
             ::retry/delay (fn [n ms ex]
                             (min (retry/delay-exp n)
                                  5000))}
```

Every `init` returns a hashmap. Internally, these are called specs. These
hashmaps are not in any way special. They can, and should, be treated as regular
hashmaps.

You can pass them around:

```clj
(let [retry (retry/init {::retry/retry? (fn [n ms ex]
                                          (< n 10))
                         ::retry/delay (fn [n ms ex]
                                         (min (retry/delay-exp n)
                                              5000))})]
  (retry/with-retry retry
    (run)))
```


You can def them:

```clj
(def retry
  (retry/init {::retry/retry? (fn [n ms ex]
                                (< n 10))
               ::retry/delay (fn [n ms ex]
                               (min (retry/delay-exp n)
                                    5000))}))
```

You can use [Register](#register) them:

```clj
(reg/register! ::github
               (retry/init {::retry/retry? (fn [n ms ex]
                                             (< n 10))
                            ::retry/delay (fn [n ms ex]
                                            (min (retry/delay-exp n)
                                                 5000))}))
```

You can merge them:

```clj
(merge (retry/init {::retry/retry? (fn [c dur ex]
                                     (< c 10))
                    ::retry/delay (constantly 1000)})
       (to/init {::to/timeout-ms 500})
       (fallback/init {::fallback/fallback (fn [ex]
                                             :default-val!)})
       (cb/init {::cb/next-state (partial cb/next-state:default
                                          {:fail-pct 0.5
                                           :slow-pct 0.5
                                           :wait-for-count 10
                                           :open->half-open-after-ms 1000})
                 ::cb/hist-size 100
                 ::cb/half-open-tries 10
                 ::cb/slow-call-ms 100})
       (rl/init {::rl/bucket-size 10
                 ::rl/period-ms 1000
                 ::rl/wait-timeout-ms 100})
       (bh/init {::bh/concurrency 10
                 ::bh/wait-timeout-ms 100}))
```

You can tack them into your components on startup (this is what I do most of the time):

```clj
(defmethod ig/init-key ::my-component [k args]
  (merge args
         (retry/init {::retry/retry? (fn [n ms ex]
                                       (< n 10))
                      ::retry/delay (fn [n ms ex]
                                      (min (retry/delay-exp n)
                                           5000))})))
```

Or all of the above!

### Overriding Values
You can override values at runtime for stateless specs:

```clj
(def retry
  (retry/init {::retry/retry? (fn [n ms ex]
                                (< n 10))
               ::retry/delay (fn [n ms ex]
                               (min (retry/delay-exp n)
                                    5000))}))

(retry/with-retry (assoc retry
                    ::retry/retry? (fn [n ms ex]
                                     ;; only retry 3 times for this code path
                                     (< n 3)))
  (run))
```

Stateless specs are:
* [Fallback](#fallback)
* [Retry](#retry)
* [Timeout](#timeout)

### Exceptions
Fusebox only throws `ExceptionInfo`s. All Fusebox exceptions will have `ex-data`
with the key `com.potetm.fusebox/error` and a keyword value that indicates the
error condition triggered (e.g. `com.potetm.fusebox.error/exec-timeout`).

### Why `tools.logging`?
There is exactly one spot which cannot be reached in application code where you
probably want some feedback: In the [retry](#retry) utility, once it's been
decided that a retry will happen, and it's about to call `Thread/sleep`. The
only options for getting feedback are: add logging in Fusebox, or add a callback.
I've opted for the former.


### Clojurescript
Every utility has a corresponding `.cljs.` namespace:

```
com.potetm.fusebox.cljs.bulkhead
com.potetm.fusebox.cljs.circuit-breaker
com.potetm.fusebox.cljs.fallback
com.potetm.fusebox.cljs.memoize
com.potetm.fusebox.cljs.rate-limit
com.potetm.fusebox.cljs.registry
com.potetm.fusebox.cljs.retry
com.potetm.fusebox.cljs.timeout
```

The api for each utility is identical to its Java counterpart with two
exceptions.

First, every utility accepts and returns Promises rather than regular forms/fns.
For example:

```clj
(-> (retry/with-retry (retry/init {::retry/retry? (fn [n ms ex]
                                                    (< n 10))
                                   ::retry/delay (constantly 1)})
      (js/Promise.resolve :done!))
    (.then println))
```

Second, `with-timeout` accepts an optional [AbortController](https://developer.mozilla.org/en-US/docs/Web/API/AbortController)
that you can pass to `fetch` to properly terminate network calls:

```clj
(to/with-timeout [abort-controller] (to/init {::to/timeout-ms 1})
  (js/fetch "https://httpbin.org/delay/1"
            (js-obj
              "signal" (.-signal abort-controller))))
```

## Acknowledgements
This library pulls heavily from [Resilience4J](https://resilience4j.readme.io/). I owe
them a huge debt of gratitude for all of their work.

[Failsafe](https://failsafe.dev/) was an inspiration for early versions of Fusebox
and for the [Fallback](#fallback) utility.

Benchmarks were acquired using [JMH](https://github.com/openjdk/jmh) and
[jmh-clojure](https://github.com/jgpc42/jmh-clojure/). These uncovered some
performance problems that triggered small design changes.

## License
Copyright © 2016-2024 Timothy Pote

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
