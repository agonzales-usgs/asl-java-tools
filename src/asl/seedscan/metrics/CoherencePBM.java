/*
 * Copyright 2012, United States Geological Survey or
 * third-party contributors as indicated by the @author tags.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/  >.
 *
 */
package asl.seedscan.metrics;

import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.Calendar;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;

import asl.metadata.*;
import asl.metadata.meta_new.*;
import asl.seedsplitter.DataSet;

import timeutils.Timeseries;

public class CoherencePBM
extends PowerBandMetric
{
    private static final Logger logger = Logger.getLogger("asl.seedscan.metrics.CoherencePBM");

    @Override public long getVersion()
    {
        return 1;
    }

    @Override public String getBaseName()
    {
        return "CoherencePBM";
    }

    public void process()
    {
        System.out.format("\n              [ == Metric %s == ]\n", getName() ); 

   // Grab station metadata for all channels for this day:
        StationMeta stnMeta = metricData.getMetaData();

   // Create a 3-channel array to use for loop
        ChannelArray channelArray = new ChannelArray("00","LHZ", "LH1", "LH2");

        ArrayList<Channel> channels = channelArray.getChannels();

        metricResult = new MetricResult();

   // Loop over channels, get metadata & data for channel and Calculate Metric

        String outFile; // Use for spec outs

        for (Channel channel : channels){

            ChannelMeta chanMeta = stnMeta.getChanMeta(channel);
            if (chanMeta == null){ // Skip channel, we have no metadata for it
                System.out.format("%s Error: metadata not found for requested channel:%s --> Skipping\n"
                                  ,getName(), channel.getChannel());
                continue;
            }

            ArrayList<DataSet>datasets = metricData.getChannelData(channel);
            String dataHashString = null;

            if (datasets == null){ // Skip channel, we have no data for it
                System.out.format("%s Error: No data for requested channel:%s --> Skipping\n"
                                  ,getName(), channel.getChannel());
                continue;
            }
         // Temp hack to get data hash:
            else {
                dataHashString = datasets.get(0).getDigestString();
            }

            if (!metricData.hashChanged(channel)) { // Skip channel, we don't need to recompute the metric
                System.out.format("%s INFO: Data and metadata have NOT changed for this channel:%s --> Skipping\n"
                                  ,getName(), channel.getChannel());
                continue;
            }

            Channel channelX = null;
            Channel channelY = null;
            if (channel.getChannel() == "LHZ") {
                channelX = new Channel("00", "LHZ");
                channelY = new Channel("10", "LH1");
                //channelY = new Channel("10", "LHZ");
            }

         // If we're here, it means we need to (re)compute the metric for this channel:

         // Compute/Get the 1-sided psd[f] using Peterson's algorithm (24 hrs, 13 segments, etc.)

            CrossPower crossPower = getCrossPower(channelX, channelX);
            double[] Gxx   = crossPower.getSpectrum();
            double dfX     = crossPower.getSpectrumDeltaF();

            crossPower     = getCrossPower(channelY, channelY);
            double[] Gyy   = crossPower.getSpectrum();
            double dfY     = crossPower.getSpectrumDeltaF();

            crossPower     = getCrossPower(channelX, channelY);
            double[] Gxy   = crossPower.getSpectrum();

            double df      = dfX;
// Temp hack, remove this:
            double[] psd   = Gxx;


         // nf = number of positive frequencies + DC (nf = nfft/2 + 1, [f: 0, df, 2df, ...,nfft/2*df] )
            int nf        = psd.length;
            double freq[] = new double[nf];
            double gamma[]= new double[nf];

         // Convert the psd to dB and fill freq array
            for ( int k = 0; k < nf; k++){
                freq[k] = (double)k * df;
                gamma[k]= (Gxy[k]*Gxy[k]) / (Gxx[k]*Gyy[k]);
                //gamma[k]= Math.sqrt(gamma[k]);
            }
            gamma[0]=0;
            Timeseries.timeoutXY(freq, gamma, "Gamma");
            Timeseries.timeoutXY(freq, Gxx, "Gxx");
            Timeseries.timeoutXY(freq, Gyy, "Gyy");
            Timeseries.timeoutXY(freq, Gxy, "Gxy");
// To Do: Figure out why gamma is > 1 and then compute average within powerband (below)
System.exit(0);

         // Convert psd[f] to psd[T]
         // Reverse freq[] --> per[] where per[0]=shortest T and per[nf-2]=longest T:

/**
            double[] per      = new double[nf];
            double[] gammaPer = new double[nf];
         // per[nf-1] = 1/freq[0] = 1/0 = inf --> set manually:
            per[nf-1] = 0;  
            for (int k = 0; k < nf-1; k++){
                per[k]     = 1./freq[nf-k-1];
                gammaPer[k]  = gamma[nf-k-1];
            }
            double Tmin  = per[0];    // Should be = 1/fNyq = 2/fs = 0.1 for fs=20Hz
            double Tmax  = per[nf-2]; // Should be = 1/df = Ndt

            PowerBand band    = getPowerBand();
            double lowPeriod  = band.getLow();
            double highPeriod = band.getHigh();

            if (lowPeriod >= highPeriod) {
                StringBuilder message = new StringBuilder();
                message.append(String.format("CoherencePBM Error: Requested band [%f - %f] has lowPeriod >= highPeriod\n"
                    ,lowPeriod, highPeriod) );
                throw new RuntimeException(message.toString());
            }
        // Make sure that we only compare within the range of useable periods/frequencies for this channel
            if (lowPeriod < Tmin || highPeriod > Tmax) {
                StringBuilder message = new StringBuilder();
                message.append(String.format("CoherencePBM Error: Requested band [%f - %f] lies outside Useable band [%f - %f]\n"
                    ,lowPeriod, highPeriod, Tmin, Tmax) );
                throw new RuntimeException(message.toString());
            }
**/

/**
        // Compute deviation from NLNM within the requested period band:
            double deviation = 0;
            int nPeriods = 0;
            for (int k = 0; k < per.length; k++){
                if (per[k] >  highPeriod){
                    break;
                }
                else if (per[k] >= lowPeriod){
                    double difference = psdInterp[k] - NLNMPowers[k];
                    //System.out.format("== NLNMPeriods[k=%d]=%.2f psdInterp[k]=%.2f NLNMPowers[k]=%.2f difference=%.2f\n",
                    //   k, NLNMPeriods[k], psdInterp[k], NLNMPowers[k], difference);
                    deviation += Math.sqrt( Math.pow(difference, 2) );
                    nPeriods++;
                }
            }

            if (nPeriods == 0) {
                StringBuilder message = new StringBuilder();
                message.append(String.format("CoherencePBM Error: Requested band [%f - %f] contains NO periods within NLNM\n"
                    ,lowPeriod, highPeriod) );
                throw new RuntimeException(message.toString());
            }
            deviation = deviation/(double)nPeriods;

            String key   = getName() + "+Channel(s)=" + channel.getLocation() + "-" + channel.getChannel();
            String value = String.format("%.2f",deviation);
            metricResult.addResult(key, value);

            System.out.format("%s-%s [%s] %s %s-%s ", stnMeta.getStation(), stnMeta.getNetwork(),
              EpochData.epochToDateString(stnMeta.getTimestamp()), getName(), chanMeta.getLocation(), chanMeta.getName() );
            System.out.format("nPeriods:%d deviation=%.2f) %s %s\n", nPeriods, deviation, chanMeta.getDigestString(), dataHashString); 
**/

        }// end foreach channel

    } // end process()


} // end class

