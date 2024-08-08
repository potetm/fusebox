# Fusebox
An extremely lightweight resilience library for Clojure

## Current Release
```clj
com.potetm/fusebox {:mvn/version "0.1.0-SHAPSHOT"}
```

## Rationale
Resilience libraries—both in Java and in Clojure—are heavyweight, have dozens of
options, are callback-driven, and have extremely complicated execution models.

Clojure is a simple language. We deserve a simple resilience library.

Fusebox was designed to have the following properties:

* Fast
* Prefer pure functions to additional options
* Modular (load only what you need)
* Linear execution
* No callbacks
* Prefer Virtual Threads when available
* Use simple, un-nested hashmaps with namespaced keys
* Zero dependencies
* Support a variety of usage patterns

Lastly, my hope is that you will look at some of the code and realize how
straightforward it is. It's almost laughable. These utilities are identical to
Resilience4j at their core, but thanks to immutable data, namespaced keys, and a
dash of macros, Clojure affords us _much_ simpler implementations.

## Usage
### Table of Contents
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
  * [spec maps](#spec-maps)
  * [Overriding Values](#overriding-values)
  * [Virtual Threads](#virtual-threads)

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
  (cb/init {::cb/next-state (partial cb/next-state:default
                                     {:fail-pct 0.5
                                      :slow-pct 0.5
                                      :wait-for-count 3
                                      :open->half-open-after-ms 100})
            ::cb/hist-size 10
            ::cb/half-open-tries 3
            ::cb/slow-call-ms 100}))

(cb/with-circuit-breaker circuit-breaker
  (run))
```

* `::cb/next-state` - fn taking the current circuit breaker and returning the next
                      state or nil if no transition is necessary. See `cb/next-state:default`
                      for a default implementation. Return value must be one of:
                      `::cb/closed`, `::cb/half-open`, `::cb/open`
* `::cb/hist-size` - The number of calls to track
* `::cb/half-open-tries` - The number of calls to allow in a `::cb/half-open` state
* `::cb/slow-call-ms` - Milli threshold to label a call slow
* `::cb/success?` - A function which takes a return value and determines
                    whether it was successful. If false, a `::cb/failure` is
                    recorded.

By far, the trickiest part of Fusebox is `::cb/next-state`. It will be run on
every invocation, so it must be fast. That said, with `cb/next-state:default` as
a guide, it's straightforward enough to implement a custom `::cb/next-state`
function. There are a variety of helpers in `com.potetm.fusebox.circuit-breaker`
to help you.

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

* Memoize makes sense for a small subset of use cases.
* `clojure.core/memoize` will re-run its fn under contention, and you probably want to avoid it.
* To show a good setup for [caching](https://github.com/ben-manes/caffeine).

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
                     body should be retried. Takes three args: eval-count,
                     exec-duration-ms, and the exception/failing value.
* `::retry/delay` - A function which calculates the delay in millis to
                    wait prior to the next evaluation. Takes three args:
                    eval-count, exec-duration-ms, and the exception/failing value.
* `::success?` - (Optional) A function which takes a return value and determines
                 whether it was successful. If false, body is retried.
                 Defaults to (constantly true).

There are a few in `com.potetm.fusebox.retry` that will help you write a
`::retry/delay` fn:

* `delay-exp`
* `delay-linear`
* `jitter` — Used in tandem with a base delay, e.g. `(jitter 10 (delay-linear 100 count))`

To aid in diagnostic feedback, two dynamic vars are provided:

* `com.potetm.fusebox.retry/*retry-count*` - number of retries attempted (starts at zero)
* `com.potetm.fusebox.retry/*exec-duration-ms*` - total execution duration in millis

These are bound prior to invocation of your body, so you should feel free to use
them in order to e.g. log or send events:

```clj
(retry/with-retry retry
  (when (pos? retry/*retry-count*)
    (log/warn "Retrying my call!"
              {:retry-count retry/*retry-count*}))
  (run))
```

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
* `::interrupt?` - bool indicated whether a timed-out thread should be interrupted on timeout

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
will suffice.

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
Every utility (except [memoize](#memoize)) is designed to take hashmaps that _don't_
include the keys that it needs. `nil` is supported as well. In those cases, calling the
utility is a pass-through. The provided body is executed as-is.

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

### spec maps
Every `init` returns a hashmap. Internally, these are called specs. These hashmaps
are not in any way special. They can, and should, be treated as regular hashmaps.

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

### Virtual Threads
Fusebox will detect if Virtual Threads are available in your JVM, and if so, it
will use them. If you AOT your Clojure code, you want to make sure the JVM you
use to compile is the same as the JVM you use in production. (This is generally
true, but especially true for Fusebox.)

Virtual Threads should be avoided under certain scenarios (specifically to avoid
[pinning](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html#GUID-704A716D-0662-4BC7-8C7F-66EE74B1EDAD)).
In the unlikely event that your application must make heavy use of `synchronized`
blocks, you'll want to disable them with the startup flag `-Dfusebox.usePlatformThreads=true`:

```
clj -J-Dfusebox.usePlatformThreads=true ...
```

## Acknowledgements
This library pulls heavily from [Resilience4J](https://resilience4j.readme.io/). I owe
them a huge debt of gratitude for all of their work.

Also, [Failsafe](https://failsafe.dev/) was an inspiration for early versions of
Fusebox and for the [Fallback](#fallback) utility.

## License
Copyright © 2016 Timothy Pote

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
