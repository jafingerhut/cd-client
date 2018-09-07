# cd-client

A client for the http://clojuredocs.org API

Note: ClojureDocs stopped supporting an HTTP-based programmatic API
for retrieving information about its documentation in 2014, and has
not re-enabled it since then.  Thus most of the functions in this
library that attempt to connect to `http://api.clojuredocs.org` will
hang and eventually time out with failure.

While it is still possible to use the `set-local-mode!` of this
library with a local data file on disk, the latest data file available
in this repo is from 2014.  That file was created near the last time
that ClojureDocs had a working programmatic API.

It would certainly be possible to use screen-scraping techniques to
update the data from ClojureDocs.org human-readable pages, but there
is no code I am aware of that does this.


## Usage

    (use 'cd-client.core)
    
Examples work with strings for the ns and name, as well as just a
symbol (no quoting needed -- it will not work if you do).  The symbol
can be of a special form (e.g. let, try, catch), macros (e.g. for,
areduce), or functions.  Use 'cdoc' to pretty-print the normal Clojure
doc string and the following information from clojuredocs.org:
examples, see-also list of symbols, and comments.

    (cdoc "clojure.core" "map")
    (cdoc map)
    (cdoc clojure.string/join)

Search for a method using just the name or a namespace and name.

    (search "pmap")
    (search "clojure.core" "map")

Browse to the url for a symbol in your default browser (takes same
arguments as cdoc):

    (browse-to "clojure.core" "map")
    (browse-to map)

By default, all of the above will query the clojuredocs web site for
the results.  As an option, you can load a 'snapshot file' of saved
results, available in the github repository, and no Internet access is
required (except for browse-to target pages, which still require
Internet access).

    (set-local-mode! "clojuredocs-snapshot-2011-12-03.txt")

You can switch back to web mode with (set-web-mode!) or show the
current mode with (show-mode).

## As a dependency:

For Leiningen:

    [org.thnetos/cd-client "0.3.4"]

## Installation

    lein deps
    lein jar

## License

Copyright (C) 2010 Lee Hinman

Distributed under the Eclipse Public License, the same as Clojure.
