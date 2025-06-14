(ns ai.obney.grain.webserver.core
  (:require [integrant.core :as ig]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [clojure.set :as set]))

(def ^:private system
  {::server {::http/port 8080
             ::http/host "0.0.0.0"
             ::http/type :jetty
             ::http/routes #(route/expand-routes #{["/" :get (fn [_req] {:status 200 :body "Hello, world!"}) :route-name :default]})
             ::http/join? false}})

(defmethod ig/init-key ::server [_ config]
  (http/start (http/create-server config)))

(defmethod ig/halt-key! ::server [_ server]
  (http/stop server))

(defn start
  [{:http/keys [routes] :as config}] 
  (ig/init
   (cond-> (update system
                   ::server
                   merge
                   (set/rename-keys
                    config
                    {:http/port ::http/port
                     :https/host ::http/host
                     :http/join? ::http/join?
                     :http/routes ::http/routes}))
     routes 
     (assoc-in [::server ::http/routes] #(route/expand-routes routes)))))

(defn stop 
  [webserver]
  (ig/halt! webserver))


