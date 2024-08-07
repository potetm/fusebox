# Fusebox
An extremely lightweight resilience library for Clojure.

## Rationale
Resilience libraries—both in Java and in Clojure—are heavyweight, have dozens of
options, are callback-driven, and have extremely complicated execution models.

Clojure is a simple language. We deserve a simple resilience library.

Fusebox was designed to have the following properties:

* Fast
* Prefer pure functions to more options
* Modular
* No callbacks
* Linear execution
* Only depend on tools.logging


# License
Copyright © 2016 Timothy Pote

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
