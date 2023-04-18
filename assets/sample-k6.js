import { clusterStatus, defaults } from "./target/satrn.js";
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const errorRate = new Rate('Sample Errors'),
      trend = new Trend('Sample Trend');

export default function (data) {
    const res = clusterStatus(defaults.destination);

    check(res, {
        'status is 200': (r) => r.status === 200,
    }) || errorRate.add(1);

    trend.add(res.timings.duration);
}