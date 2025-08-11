# Grain

## What is this?

Grain is an "AI native" framework for building Event Sourced applications where Agentic Workflows are first-class citzens in the domain and not just a bolted on framework.

## Why did you make it?

At ObneyAI, we use [Event Modeling and Event Sourcing](https://leanpub.com/eventmodeling-and-eventsourcing) to design [Simple](https://www.youtube.com/watch?v=SxdOUGdseq4) information systems and applications for our customers. We believe in investing in our tooling, because we always want to deliver the next project faster, but with a high degree of confidence in the platforms we deliver. Being in the emerging AI space, it's our job to wade through all the uncertainty and attempt to skate to where we think the puck is heading. Grain is a toolkit that we think contains a composition of some of the best ideas for building conventional and Agentic software.

## Does it have an Agent Framework?

Yes, Grain does have an Agent Framework that is highly integrated into its ecosystem. We have taken a look at the landscape of Agent Frameworks and determined two things:

1. There are better ways to go about it than what most existing Agent Frameworks are doing.
2. Agent Frameworks are not rocket science, they are largely just orchestration engines, so it's ok to make your own if you have a good reason.

There will be further documentation on how Grain Agents work, but in short, they are powered by fully declarative [Behavior Trees](arxiv.org/abs/2404.07439), have short-term program memory, and have long-term Event Sourced memory, such that an Agent's long-term memory is a projection or snapshot over a subset of events in your Domain. This property is what makes Grain Agents first-class citizens in your Event Sourced Domain.

## Can I use it?

Yes, you can use it. Grain is MIT licensed software. We use Grain in production for our clients, but that doesn't mean it's perfect or ready in every way. Large portions of Grain are relatively stable at this point, such as the core CQRS / Event Sourcing components, but other aspects may change rapidly, such as the components that make up the Agent Framework.

One promise we can make is that we will never break your software, we enhance existing components routinely, but we avoid making breaking changes that violate existing contracts between components. If a change we want to make is revolutionary enough, we will introduce a new version of a component, that way consumers of existing versions aren't negatively impacted.

Our choice to deliver Grain as a simple system of cooperating components using [Polylith](https://polylith.gitbook.io/polylith) allows us this flexibility in addition to the ability to mix and match components in new and interesting ways to publish standalone tools as independent Polylith Projects from a single repository.

## How do I use it?

It may be a while before we have extensive documentation.

There are 2 main ways you can use Grain:

1. You can use Grain as a library if you don't forsee yourself needing to adjust any aspects of the components and framework to what you intend to build.

2. You could clone this repository and build your application directly within the framework, all of the pieces and parts would then be available for your to tweak and refine. The downside, of course, is that you would make it harder to take advantage of updates to Grain, but sometimes this level of control is a necessity.

We've tried to give a decent demonstration of how to build an application with Grain in the base in `bases/example-base` and the component in `components/example-service`. Additionally, see `development/src/example_app_demo.clj` for a demo of how to start and interact with the example system.

### Authentication / Authorization

At this time we leave these two aspects to the user, but this shouldn't be much of an issue, as the user has the option of composing their own routes together when using the webserver component or even using their own preferred webserver (this will naturally require a bit of extra labor).

### Can I just use the Agent Framework?

Sure you can. Grain really shines when you drink the Event Sourcing koolaid and model your entire domain as an Event Sourced system, but it's totally possible to just use Grain's Event Sourced, Behavior-Tree Agent Framework on its own and integrate it into your more conventional application systems.

## Available Packages

Polylith projects are how we publish various aspects of Grain such that you can include them in your deps.edn file and pull them in as dependencies. 

Here is what we currently offer:

### grain-core

This is the core set of utilities that can power what you see in the example application in this repo. It's everything you need to build an application that follows CQRS / Event Sourcing principles. It comes with an in-memory backend for the Event Store component for getting started quickly. The Event Sourced Behavior Tree Engine is included in this package.

### grain-event-store-postgres-v2

This is a Postgres backend for the Event Store component, pull it in and require the `ai.obney.grain.event-store-postgres-v2.interface` namespace to load its multimethod implementation. This will allow you to simply switch between `:in-memory` and `:postgres` with a one line change with no other code changes required. Our event-store-v2 component is protocol-driven and presents a consistent API to callers, backends are expected to implement the spec. You can even implement your own backend!

### grain-dspy-extensions

This is where the magic of Grain's Agent Framework happens. [DSPY](https://dspy.ai/) is a best in class Python library from Stanford for working with LLMs in sophisticated ways. This package includes our `clj-dspy` component and a re-useable `dspy` Behavior Tree action node for orchestrating reliable Agentic Workflows. You'll just have to see some examples to experience the magic, it's difficult to explain how cool it is. However, you do take a dependency on Python when you use these tools, so you will want to set up a `python.edn` file in the root of your project directory with the following content: `{:python-executable ".venv/bin/python"}`. Then you will need to create a python virtual environment called `.venv` in the root of your directory using a tool of your choice. We like [uv](https://docs.astral.sh/uv/). You'll need to install at least Python `3.12`.

We think the dependency on Python is pretty neat! Python is really in the spotlight these days thanks to a lot of applied AI tooling being heavily Python based. It's fantastic that a Clojure application can combine the best of both the JVM and Python in order to create an innovative product that is more than the sum of its parts.

### grain-mulog-aws-cloudwatch-emf-publisher

Grain uses [mulog](https://github.com/BrunoBonacci/mulog) for logging and tracing. This is good for you, because it means if you have a preferred logging solution, all you have to do is implement a custom mulog publisher, intercept Grain's logs, and translate them into your own logging solution. This package is a custom publisher that we use to enable automatic creation of CloudWatch metrics in AWS for Dashboards, Alerting, and other observability use-cases.

## What the heck is Polylith?

<img src="logo.png" width="30%" alt="Polylith" id="logo">

The Polylith documentation can be found here:

- The [high-level documentation](https://polylith.gitbook.io/polylith)
- The [poly tool documentation](https://cljdoc.org/d/polylith/clj-poly/CURRENT)
- The [RealWorld example app documentation](https://github.com/furkan3ayraktar/clojure-polylith-realworld-example-app)

You can also get in touch with the Polylith Team on [Slack](https://clojurians.slack.com/archives/C013B7MQHJQ).
