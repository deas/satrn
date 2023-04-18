# satrn 🪐
[Orbiting around Apache Solr](https://youtu.be/8q8110rRTCA?t=18283).

This project started out a basic tool synchronizing collection configuration between Solr clusters (`satrn.clj`). From there, it grew along with a project bringing SRE and Continuous Delivery to Solr environments - minimalism and simplicity being key design elements.

Command line tools:

`satrn.clj`: Reconcile collections, configsets and aliases to desired state - the GitOps game. It may be helpful to know that this tool started out using a Solr server reprensenting desire state - not git. State, such as shard leaders or nodesets (creation) has no natural representation there. Hence, things have been stretched a bit and covered by tool configuration and more recently, metadata in the Solr Blob store.

Removal and destruction may be missing for some objects, but implementation should be fairly straight forward.

`update_docs.clj`:  Read documents from stdin and update them in collection.

`export_docs.clj` : Export documents from collection to stdout.

`clone.clj` : Export and update - piped shortcut.

`rnb.clj` : Restore and backup collections.

`dff.clj` : Diff json files

`cnt_qdff.clj` : Compare counts for queries

`rpc.clj` : Read RPC style command sequence from stdin and send them to Solr. Safest way to try it out is `./src/rpc.clj <test/rpc-clusterstatus.edn` (read only call).

`logs.clj` : Analyze/Visualize/Structure logs. Cloud Console/Shell is not enough.

Most of them have a `--help` option.

Unless explicitly set (e.g. using environment variables `SOLR_BASE_URL`/ `SOLR_AUTH`, `LOG_LEVEL`), the library assumes defaults `http://localhost:8983` / `solr:SolrRocks` / `info`. Setting the log level to `debug` will log http call details.

Using [`babashka`](https://babashka.org/) for portability and minimalism. [`asdf`](https://asdf-vm.com/) can be used to install `babashka` as follows:

```shell
asdf plugin add babashka
asdf plugin add k6 # if you want to try JavaScript InterOp Library
asdf install
```

There are various ways to use these tools (Script, Container, Library, Java Interop) - Don't get confused.


## Development
Run NREPL-server `bb nrepl-server` and jack in with your editor. This is the bare minimum. Visual Studio Code with Calva extension is a great editor.

Even though the Open Container Image only uses `babashka` (and not a full JVM), you might want to use `deps.edn` with a JVM for a better dev experience.

## Solr Service
To quickly get your feet wet, you can spin up a Solr cluster using `docker-compose`:

```shell
# Spin up solrcloud single node
# docker-compose up
# Spin up solrcloud multi node
docker-compose -f docker-compose.yml -f docker-compose-multi-solr.yml up
# Set default solr:SolrRocks auth
# docker exec -i satrn_solr-1_1 sh -c 'cat >/tmp/security.json && solr zk cp /tmp/security.json zk:security.json -z zoo-1:2181' <assets/security.json

# Create a single shard collection with 3 (requires multi node version)
./src/rpc.clj <test/rpc-create-collection.edn

# Create a single shard collection with 1 replica (requires multi node version)
sed -e s/'replicationFactor.*'/'replicationFactor "1"'/g \
  <test/rpc-create-collection.edn | ./src/rpc.clj 
```

## Usage

Run sync application:

```shell
export config=config-default.edn
./src/satrn.clj sync # Run w/o argument to get help

```

Run the project's tests:
```shell
./test-runner.clj
```

Build uberscripts (bake relatated use cases into single files):
```shell
bb build
```

Build AOT- uberjar (Java InterOp):
```shell
clj -T:build uber
```

Using `rnb` from Java `main`:
```shell
java -classpath target/satrn-*-standalone.jar satrn.RnB
```

Build Node Library (CommonJS InterOp - targeting `k6`):
```shell
clj -A:shadow-cljs release :lib
```
Running basic `k6` sample (using InterOp) 
```shell
k6 run --http-debug=full -i 1 ./sample-k6.js
```

Build and run an Open Container Image:
```shell
TAG=satrn:0
docker build -t ${TAG}  .
docker run --rm ${TAG}
# Run with custom config 
# docker run --rm -v `pwd`/config-sample.edn:/config-default.edn ${TAG}
```

Use as a library from Clojure
```shell
clojure -Sdeps '{:deps {io.github.deas/satrn {:sha "..." }}}' \
    -e "(require '[satrn.command :as c])(-> (c/cmd-cluster-status) c/execute c/response-map)"
```

Run update on Kubernetes using image from private registry:

```shell
K8S_CTX=default
K8S_NS=search
K8S_IMAGE=ghcr.io/deas/satrn:latest
zcat collection.json.gz | \
  kubectl --context $K8S_CTX run update-docs --rm -i --image $K8S_IMAGE \
  --overrides='{"apiVersion": "v1", "spec": {"template": {"spec": { "imagePullSecrets": [{"name": "regcred"}]}}}}' \
  -- bb /update_docs.clj -d '{:destination {:base-url "http://solr-ingress:8983", :basic-auth ["solr" "SolrRocks"]}}' collection
```

## Hints
- Solr 9 is expected to ship functionality around replica placement that may be worth checkout out
- Logging is configurable, but currently optimized for Google Cloud Platform
- `satrn.clj` exposes Prometheus metrics

## TODO
- ~~Refactor for use as a library~~
- ~~Leverage `babashka` tasks for build~~
- Leverage github actions cache
- Beware: `config.edn` in classpath still appears overlay `config` environment (-> `test`)
- Consistently use default at latest possible moment - not in CLIs (-> testing)
- Remove TODO: tags in code
- Refactor to use sub-commands (appears cli-matic/spec is fine with `babashka` now? Or `docopt.clj`?)
- Update credentials in `security.json`
- ~~Export config to folder/zip?~~
- ~~Discover Solr server version and support recent (source) versions~~
- ~~`satrn.clj` Config: Externalize secrets?~~
- Clean up config maps/merging
- Improve test coverage
- Improve jvm interop
- Improve node-/`k6` library target (Idea to leverage code from `k6` came later so this is a bit of a retrofit)
- Shim `k6/http` for node repl exploration
- Improve validation and error handling
- Leverage nested diagnostic context for collections.
- ~~`satrn.clj` : Suppport for stored queries~~
- `logs.clj` Streaming : `gcloud alpha logging tail`/ raw Kubernetes support (likely needs timestamp sorting)
- Release helm chart to github (pages?), probably https://github.com/helm/chart-releaser-action and/or https://github.com/helm/chart-releaser
- Migrate `asdf` -> `nix`
- Try out Solr-9

