(ns satrn.serve
  (:require [satrn.docs :as d]
            [satrn.misc :as m]
            [satrn.command :as c]
            ;; [satrn.git :as g]
            [org.httpkit.server :as srv]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [infof errorf]]
            [clojure.core.match :refer [match]]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [hiccup.core :as h]))

;; https://www.cssportal.com/html-colors/orig-16-colors.php
(def colors {:excluded ["black"	"white"]
             :replica ["red" "lime" "blue" "yellow" "cyan" "magenta" "fuchsia"
                       "silver" "gray" "maroon" "olive" "green" "purple" "teal" "navy"]
             :node []})

(def style {:jumbotron ["p-6" "shadow-lg" "rounded-lg" "bg-gray-100" "text-gray-700"]
            :td ["text-sm" "text-gray-900" "font-light" "px-6" "py-4" "whitespace-nowrap"]
            :select ["form-select" "appearance-none" "block" "w-full" "px-3" "py-1.5"
                     "text-base" "font-normal" "text-gray-700" "bg-white" "bg-clip-padding" "bg-no-repeat"
                     "border" "border-solid" "border-gray-300" "rounded" "transition" "ease-in-out" "m-0"
                     "focus:text-gray-700" "focus:bg-white" "focus:border-blue-600"]
            :input ["form-control" "block" "w-full" "px-3" "py-1.5" "text-base" "font-normal" "text-gray-700"
                    "bg-white" "bg-clip-padding" "border" "border-solid" "border-gray-300"
                    "rounded" "transition" "ease-in-out" "m-0" "focus:text-gray-700" "focus:bg-white"
                    "focus:border-blue-600" "focus:outline-none"]})

(def metrics (atom nil))

(def config (atom nil))

(def server (atom nil))

(defn collection-names
  [flt]
  (->> @metrics
       :collections
       :events
       keys
       (map name)
       (filter #(re-matches (re-pattern flt) %))))

;; TODO: This should probably go to solr-metrics
(defn document-ratios
  []
  (let [;; TODO: Just one re + fieldset atm
        cm (-> @config :destination :collection-metrics first)
        [re fields] cm]
    (->> (when fields (collection-names re))
         (map (fn [c] [(keyword c)
                       (into {} (d/doc-ratio (map name fields)
                                             (:destination @config)
                                             c))]))
         (into {}))))

(defn init! [cfg]
  (reset! config cfg)
  (let [dest-colls (->> (:sources cfg)
                        (map (fn [source]
                               (map (fn [coll-kw]
                                      (keyword (str (:dest-prefix source)
                                                    (name coll-kw))))
                                    (if (-> source :base-url (str/starts-with? "http"))
                                      (-> source :collections)
                                      (->> (m/source-coll-dirs source)
                                           (map #(-> (.getName %) keyword)))))))
                        flatten)]
    (reset! metrics
            {:globals {:errors 0
                       :config-deletions 0}
             :collections {:events (->> dest-colls
                                        (map (fn [coll-name]
                                               [coll-name {:update-errors 0
                                                           :updates 0
                                                           :rebalances 0
                                                           :rebalance-errors 0}]))
                                        (into {}))}})
    dest-colls))

(defn inc-coll-total
  [coll-name-kw type]
  (swap! metrics update-in [:collections :events coll-name-kw type] inc))

(defn inc-total
  [type]
  (swap! metrics update-in [:globals type] inc))

;; quick hack
(defn query-params [req]
  (into {} (for [[_ k v] (re-seq #"([^&=]+)=([^&]+)" req)]
             [(keyword k) v])))

(defn all-pairs [coll]
  (when-let [s (next coll)]
    (lazy-cat (for [y s] [(first coll) y])
              (all-pairs s))))

(defn replica-labels
  [{:keys [state leader]}]
  (str/join (cond-> [(case state
                       "active" "😀"
                       "recovering" "🤕"
                       "down" "☠️")]
              (= "true" leader) (conj "🎯"))))

(defn dot-replica
  [replica]
  (str
   (str/replace-first (:core replica) "_shard1_replica" "")
   (replica-labels replica)))

(defn graph-nodes
  [cluster & {:keys [flt] :or {flt ".*"}}]
  (let [collections (->> (:collections cluster)
                         (filter #(re-matches (re-pattern flt) (name (first %)))))
        ccnt (count (:replica colors))
        cmap (into {} (map-indexed (fn [i k] [k (mod i ccnt)]) (keys collections)))
        replicas (-> (map (fn [[cname cval]]
                            (-> (map (fn [[_ rval]]
                                       (merge rval {:collection cname
                                                    :cidx (cname cmap)}))
                                     (->> cval :shards :shard1 :replicas))
                                flatten))
                          collections)
                     flatten)]
    [cmap
     (-> (map (fn [[cname cval]]
                [cname (-> (map (fn [rval]
                                  ;; hacky but we need state
                                  (dot-replica rval))
                                (->> cval :shards :shard1 :replicas vals))
                           flatten)])
              collections))
     (reduce (fn [a v]
               (let [node (keyword (str/replace-first (:node_name v) #"\..*" ""))
                     c (node a)]
                 (assoc a node (cons v (or c [])))))
             {}
             replicas)]))


(defn dot-response
  [[cmap replicas nodes]]
  ;; health, state, leader
  (let [;; to many edges
        #_replica-edges
        #_(->> replicas
               (into {})
               vals
               (map (fn [x] (->> (all-pairs x)
                                 (map (fn [[a b]]
                                        (str "\"" a "\" -- \"" b "\""))))))
               flatten)
        node-edges (map (fn [[n replica]]
                          (str "\"" (name n) "\" [shape=box] -- {"
                               (->> replica
                                    (map #(str "\""
                                               (dot-replica %)
                                               "\""
                                               " [color="
                                               (get (:replica colors)
                                                    ((:collection %) cmap))
                                               "] "))
                                    (str/join ";"))
                               "}")) nodes)]
    (str "digraph cluster {\nsize=\"6,6\";\nnode [color=lightblue, style=filled];\n"
         (str/join "\n" node-edges)
         ;; "\n" (str/join "\n" replica-edges)
         "\n}")))


(defn js-coll-metrics-response
  [{:keys [query-string]}]
  (let [params (when query-string (query-params query-string))
        {:keys [include] :or {include ".*"}} params
        time (System/currentTimeMillis)]
    (->> @metrics
         :collections
         :events
         (filter (fn [[k _]] (re-matches (re-pattern include) (name k))))
         (map (fn [[k v]] (merge {:time time
                                  :collection k} v)))
         json/generate-string)))

(defn js-doc-ratio-metrics-response
  [_]
  (-> (document-ratios) json/generate-string))

(defn network-response
  [{:keys [query-string]}]
  (let [params (when query-string (query-params query-string))
        {:keys [include] :or {include ".*"}} params]
    (-> (c/cluster-status (:destination @config))
        :cluster
        (graph-nodes :flt include)
        dot-response)))

(defn logger-response
  [{:keys [query-string]}]
  (let [params (when query-string (query-params query-string))
        [logger level] (-> params :set m/url-decode (str/split #":"))
        destination (:destination @config)
        basic-auth (:basic-auth destination)
        live-url-base (->> destination
                           c/cluster-status
                           c/live-nodes
                           (map #(str "http://" (str/replace % #"_solr$" ""))))]
    (doall (pmap #(-> (c/cmd-logging logger level)
                      (c/execute {:base-url %
                                  :basic-auth basic-auth})
                      :status)
                 live-url-base))
    (h/html [:td.transition.duration-1000 {:_ "on load add .bg-green-100 then settle then remove .bg-green-100"} logger])))


(defn logging-response
  [{:keys [query-string]}]
  (let [params (when query-string (query-params query-string))
        {:keys [include] :or {include ".*"}} params
        #_{:keys [loggers levels] :as logging}
        logging (-> (c/cmd-logging)
                    (c/execute (:destination @config))
                    (c/response-map))
        levels (cons "-" (:levels logging))
        loggers (->> logging
                     :loggers
                     (map #(assoc % :level (if (:set %) (:level %) "-"))))
        options (fn [logger level]
                  (map (fn [l]
                         [:option
                          (cond-> {:value (str logger ":" l)}
                            (= level l) (assoc :selected "selected"))
                          l])
                       levels))]

    (h/html (into [:tbody]
                  (->> loggers
                       (filter #(re-matches (re-pattern include) (:name %)))
                       (map-indexed (fn [i {:keys [name level]}]
                              ;; {:label name :value name}
                                      [:tr.border-b {:class [(if (= 0 (mod i 2)) "bg-gray-100" "bg-white")]}
                                       [:td {:id (str "logger-" i)
                                             :class (:td style)} name]
                                       [:td {:class (:td style)}
                                        (into [:select {:class (:select style)
                                                        :hx-get "./logger"
                                                        :hx-target (str "#logger-" i)
                                                        :hx-indicator ".htmx-indicator"
                                                        :name "set"}] (options name level))]])))))))

(defn prom-metrics-response
  []
  (str
   "# HELP satrn_collection_totals The total number of actions on collections\n"
   "# TYPE satrn_collection_totals counter\n"
   (->> (map (fn [[c m]]
               (map (fn [[k v]]
                      (str "satrn_collection_totals{name=\"" (name c) "\",type=\"" (name k) "\"} " (format "%.1f" (float v)) #_opt_ts))
                    m))
             (-> @metrics :collections :events))
        flatten
        (str/join "\n"))
   "\n"
   "# HELP satrn_document_ratios Document ratios\n"
   "# TYPE satrn_document_ratios gauge\n"
   (try
     (->> (document-ratios)
          (map (fn [[c m]]
                 (map (fn [[k v]]
                        (str "satrn_document_ratios{collection=\"" (name c) "\",field=\"" (name k) "\",range=\"1DAY\"} " (format "%.2f" (float v))))
                      m)))
          flatten
          (str/join "\n"))
     (catch Exception e
       (inc-total :errors)
       (errorf "Exception caught getting document rations: %s"
               e)))
   "\n"
   "# HELP satrn_totals The total number of non collection scoped events\n"
   "# TYPE satrn_totals counter\n"
   (->> (map (fn [[k v]]
               (str "satrn_totals{name=\"" (name k) "\"} " (format "%.1f" (float v)) #_opt_ts))
             (:globals @metrics))
        flatten
        (str/join "\n"))
   "\n"))

(defn content
  [& children]
  [:div.flex.flex-row.flex-wrap.py-4
   [:aside {:class ["w-full" "sm:w-1/3" "md:w-1/4" "px-2]"]}
    [:div.sticky.top-0.p-4.w-full
     (let [hx-param {:hx-push-url "true"
                     :hx-target "#content"}]
       [:ul.flex.flex-col.overflow-hidden
        [:li [:a {:href "./"} "Solr Cluster Network"]] ;; 🪐
        [:li [:a (assoc hx-param :hx-get "./?page=config") "Configuration"]]
        [:li [:a (assoc hx-param :hx-get "./?page=logging") "Logging"]]
        [:li [:a (assoc hx-param :hx-get "./?page=metrics") "Metrics"]]
        [:li [:a {:href "./metrics"} "Prom Metrics"]]
        [:li [:a (assoc hx-param :hx-get "./?page=restore") "Restore"]]])]]
   (apply conj
          [:main {:class ["w-full" "sm:w-2/3" "md:w-3/4" "pt-1" "px-2" "space-y-4"]
                  :role "main"}
           [:div#error.hidden.transition.duration-1000.bg-red-100.rounded-lg.py-5.px-6.mb-4.text-base.text-red-700.mb-3
            {:role "alert"}
            "A simple danger alert - check it out!"]]
          children)])

(defmulti render-page #(:page %))

(defmethod render-page "network" [{:keys [include] :or {include ".*"}}]
  (content
   [:header.header
    [:div {:class (:jumbotron style)}
     [:h2 {:class ["font-semibold" "text-3xl" "mb-5"]} "Solr Cluster Network"]
     [:p "Display nodes and replicas from the Solr cluster. Replicas of a shard are displayed with the same color. Replica state is represented by 😀,🤕 and ☠️ mapping to active, recovering and down. Shard leadership is represented by 🎯."]]]
   [:form
    {:hx-get "./"
     :hx-push-url "true"
     :hx-target "#content"}
    [:input {:type "hidden" :name "page" :value "network"}]
    [:input
     {:class (:input style)
      :name "include"
      :placeholder include
      :autofocus ""}]]
   [:div#network.h-screen #_{:style {:height "700px"}}
    [:script (str "fetch('./network?include=" include "').then(response => response.text()).then(dot => {var el = document.getElementById('network'),data = vis.parseDOTNetwork(dot); new vis.Network(el, data);});")]]))

(defmethod render-page "restore" [_]
  (content
   [:header.header
    [:div {:class (:jumbotron style)}
     [:h2 {:class ["font-semibold" "text-3xl" "mb-5"]} "Restore"]
     [:p "Could do collection restoration here"]]]))

(defmethod render-page "logging" [_]
  (content
   [:header.header
    [:div {:class (:jumbotron style)}
     [:h2 {:class ["font-semibold" "text-3xl" "mb-5"]} "Logging"]
     [:p "Temporarily set log level for all Solr nodes"]]]
   [:input {:class (:input style)
            :type "search"
            :name "include"
            :placeholder "<Logger Regex><Ret>"
            :hx-get "./logging"
            :hx-trigger "keyup changed delay:500ms, search"
            :hx-target "#search-results"
            ;; .hx-indicator ".htmx-indicator"
            }]
   [:table#search-results.table.min-w-full #_[:thead [:tr [:th "logger"]]]]))

(defmethod render-page "config" [_]
  (content
   [:header.header
    [:div {:class (:jumbotron style)}
     [:h2 {:class ["font-semibold" "text-3xl" "mb-5"]} "Configuration"]
     [:p "Configuration of this service"]]]
   [:pre
    (with-out-str (pprint (select-keys @config [:documentation :sources :destination :pause #_:output-fn])))]))

(defmethod render-page "metrics" [_]
  (content
   [:header.header
    [:div {:class (:jumbotron style)}
     [:h2 {:class ["font-semibold" "text-3xl" "mb-5"]} "Metrics"]
     [:p "Absolute number of actions on collection - filtered by collection name."]]]
   [:div#vis.min-w-full]
   [:script (str #_"var interval = 10000,
                       elId = '#vis',
                       visName = 'metrics',
                       url = './metrics-coll-js?include=(sample-1|sample-2)';"
             #_(-> (io/resource "line-metrics.js") slurp)
             #_(-> (io/resource "bar-metrics.js") slurp)
             "var interval = 10000,
    elId = '#vis',
    url = './metrics-coll-js?include=(sample-1|sample2)',
    visName = 'line-metrics',
    samples = 30,
    yField = 'updates',
    spec = {
        $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
        description: 'A dynamic line chart',
        data: {
            name: visName,
            url: url
        },
        width: 'container',      
        mark: 'line',
        encoding: {
            x: {
                field: 'time',
                timeUnit: 'hoursminutesseconds'
            },
            y: {
                field: yField,
                type: 'quantitative'
            },
            color: {
                field: 'collection',
                type: 'nominal'
            }
        }
    };
vegaEmbed(elId, spec).then(function (result) {
    window.setInterval(function () {
        fetch(url)
            .then(response => response.json())
            .then(data => {
                var currentData = result.view.data(visName),
                    timesCnt = currentData.length / data.length,
                    removeTime = timesCnt == samples ? currentData[0].time : 0,
                    changeSet = vega
                    .changeset()
                    .insert(data)
                    .remove(function (t) {
                        return t.time <= removeTime;
                    });
                result.view.change(visName, changeSet).run();
            });
    }, interval);
})")]))

(defn template
  [{:keys [debug page] :as params}]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:head
     [:meta {:charset "UTF-8"}]
     [:title "Satrn Orbiting around Apache ☀️"]
     [:link {:rel "shortcut icon" :href "data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%20100%20100%22%3E%3Ctext%20y%3D%22.9em%22%20font-size%3D%2290%22%3E%F0%9F%AA%90%3C%2Ftext%3E%3C%2Fsvg%3E" :type "image/svg+xml"}]
     [:link {:rel "stylesheet" :href "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css"}]
     [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap"}]
     [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/tw-elements/dist/css/index.min.css"}]
     ;; [:link {:href "https://unpkg.com/tailwindcss@^2/dist/tailwind.min.css" :rel "stylesheet"}]
     [:script {:src "https://cdn.tailwindcss.com"}]
     [:script "tailwind.config = {
                 theme: {
                   extend: {
                     fontFamily: {
                      sans: ['Inter', 'sans-serif'],
                     },
                   }
                }
              }"]
     ;; https://vega.github.io/vega-lite/usage/debugging.html
     [:script {:src (str "https://cdn.jsdelivr.net/npm/vega@5" (when debug "/build/vega.js"))}]
     [:script {:src (str "https://cdn.jsdelivr.net/npm/vega-lite@5" (when debug "/build/vega-lite.js"))}]
     [:script {:src (str "https://cdn.jsdelivr.net/npm/vega-embed@6" (when debug "/build/vega-embed.js"))}]
     [:script {:src "https://unpkg.com/vis-network@9.1.0/standalone/umd/vis-network.min.js"}]

     [:script {:src "https://unpkg.com/htmx.org@1.5.0/dist/htmx.min.js" :defer true}]
     ;; [:script {:src "https://unpkg.com/htmx.org/dist/ext/class-tools.js" :defer true}]
     [:script {:src "https://unpkg.com/hyperscript.org@0.8.1/dist/_hyperscript.min.js" :defer true}]]
    [:body
     [:div#content.container.mx-auto
      ;; then transition opacity to 0 ;; all ease-in 1s
      {:_ "on htmx:error(errorInfo) put errorInfo.error into #error then remove .hidden from #error then settle then wait 3s then add .hidden to #error"}
      (render-page params)]
     [:footer.mt-auto]
     [:script {:src "https://cdn.jsdelivr.net/npm/tw-elements/dist/js/index.min.js"}]
     #_[:script "document.body.addEventListener('htmx:responseError', function(evt) {debugger;});"]])))


(defn index [{:keys [query-string headers]}]
  (let [params (merge {:page "network"} (when query-string (query-params query-string)))
        ajax-request? (get headers "hx-request")]
    (if (and params ajax-request?)
      (h/html
       (render-page params))
      (template params))))

(defn config-path
  [name]
  (str (System/getProperty "java.io.tmpdir")
       (System/getProperty "file.separator")
       name
       ".zip"))

;; action=UPLOAD name=name
;; curl -X POST --header "Content-Type:application/octet-stream" --data-binary @test/sample_techproducts_configs.zip "http://localhost:8080/solr/admin/configs?action=UPLOAD&name=sample_techproducts_configs"
(defn upload-config-response [{:keys [query-string body]}]
  (let [path (-> query-string query-params :name config-path)]
    (io/copy body (io/file path))))

;; action=MODIFYCOLLECTION collection=name collection.configName=cfg-name
(defn modify-collection-response [{:keys [query-string]}]
  (let [path (-> query-string query-params :collection.configName config-path)]
    #_(io/copy (-> req :body)
               (io/file path))))

(defn routes [{:keys [request-method uri] :as req}]
  (let [path (vec (rest (str/split uri #"/")))]
    (match [request-method path]
      [:get []] {:body (index req)}
      [:get ["metrics"]] {:body (prom-metrics-response)}
      [:get ["metrics-coll-js"]] {:body (js-coll-metrics-response req)}
      [:get ["metrics-doc-ratio-js"]] {:body (js-doc-ratio-metrics-response req)}
      [:get ["res" id]] {:body (-> (io/resource id) slurp)}
      [:get ["network"]] {:body (network-response req)}
      [:get ["logging"]] {:body (logging-response req)}
      [(:or :get :post) ["logger"]] {:body (logger-response req)} ;; TODO: Should be post
      [:post ["solr" "admin" "configs"]] {:body (upload-config-response req)}
      [(:or :get :post) ["solr" "admin" "collections"]] {:body (modify-collection-response req)}
      :else {:status 404 :body "Error 404: Page not found"})))


(defn server-start [port]
  (infof "Starting service on port %d" port)
  (reset! server (srv/run-server #'routes {:port port :legacy-return-value? false})))

(defn server-stop []
  (infof "Stopping service")
  @(srv/server-stop! @server))

(comment

  (require '[config.core :refer [load-env]])
  ;; #'server
  (init! (load-env))
  (-> @metrics :collections :events keys)
  ;; (document-ratios)
  (inc-coll-total :sample-1 :updates)
  (server-start 8080)
  (server-stop)
  (c/live-nodes (c/cluster-status (:destination @config)))
  (let [cluster (-> (io/resource "clusterstatus-8-11.json")
                    slurp
                    (json/parse-string keyword)
                    :cluster)]
    (->> (graph-nodes cluster :flt ".*-xyz")
         dot-response
         #_flatten
         (spit "/tmp/flubber.dot"))))

