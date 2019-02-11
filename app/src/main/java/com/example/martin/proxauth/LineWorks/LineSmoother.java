package com.example.martin.proxauth.LineWorks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class LineSmoother {

    public static double mean(List<Point> points){
        double mean = 0;

        for(Point point: points){
            mean += point.getY();
        }
        mean /= points.size();

        return mean;
    }

    public static double corrCoeff(List<Point> x, List<Point> y){
        if(x.size() != y.size()) return 0;

        double mx = mean(x);
        double my = mean(y);

        double Exy = 0, Ex2 = 0, Ey2 = 0;

        for(int i=0;i<x.size();i++){
            Exy += (x.get(i).getY()-mx)*(y.get(i).getY()-my);
            Ex2 += Math.pow((x.get(i).getY()-mx),2);
            Ey2 += Math.pow((y.get(i).getY()-my),2);
        }

        return Exy/(Math.sqrt(Ex2*Ey2));
    }

    public static List<Point> sample(List<Line> lines,double start, double step, double end){

        List<Point> points = new ArrayList<>();

        for(double i=start;i<=end;i+=step){
            for(Line line: lines ){
                if((line.getPoint1().getX()<=i && line.getPoint2().getX()>= i) || (line.getPoint1().getX()>=i && line.getPoint2().getX()<= i) ){
                    double m = line.gradient();
                    double c = line.intercept();
                    double y = m*i + c;

                    points.add(new Point(i,y));
                }
            }
        }

        return points;
    }


    public static List<Point> standardize(List<Point> points){
        double min = getMin(points);
        double max = getMax(points);
        List<Point> standardized = new ArrayList<>();

        int i;
        int length = points.size();
        for(i=0;i<length;i++){
            Point point = points.get(i);
            double standard = 2*((point.getY()-min)/(max-min)) - 1;
            standardized.add(new Point(point.getX(),standard));
        }
        return standardized;
    }


    private static double getMax(List<Point> points){
        double max = points.get(0).getY();

        for (Point point: points){
            if(point.getY() > max) max = point.getY();
        }

        return max;
    }

    private static double getMin(List<Point> points){
        double min = points.get(0).getY();

        for(Point point: points){
            if(point.getY() < min) min = point.getY();
        }

        return min;
    }

    public static List<Line> smoothLine(ConcurrentHashMap<Integer, Line> lineSegments, int end){
        if(lineSegments.size() < 4){
            List<Line> lines = new ArrayList<>();
            Iterator<Integer> iterator = lineSegments.keySet().iterator();

            while(iterator.hasNext()){
                lines.add(lineSegments.get(iterator.next()));
            }

            return lines;
        }

        List<Line> smoothedLine = new ArrayList<>();
        List<Point> points = getPoints(lineSegments, end);

        smoothedLine.add(lineSegments.get(0));

        Point newPoint = points.get(1);

        for(int i = 2; i< points.size()-2; i++){
            Point lastPoint = newPoint;
            newPoint = smoothPoint(points.subList(i-2,i+3));
            smoothedLine.add(new Line(lastPoint, newPoint));
        }

        Line lastSegment = lineSegments.get(lineSegments.size()-1);
        smoothedLine.add(new Line(newPoint,new Point(lastSegment.getPoint1())));

        smoothedLine.add(lastSegment);

        return smoothedLine;
    }

    private static Point smoothPoint(List<Point> points) {

        double avgX = 0;
        double avgY = 0;

        for(Point point: points){
            avgX += point.getX();
            avgY += point.getY();
        }

        avgX /= points.size();
        avgY /= points.size();

        Point newPoint = new Point(avgX,avgY);
        Point oldPoint = points.get(points.size()/2);
         double newX = (newPoint.getX() + oldPoint.getX())/2;
         double newY = (newPoint.getY() + oldPoint.getY())/2;

        return new Point(newX,newY);
    }

    private static List<Point> getPoints(ConcurrentHashMap<Integer, Line> map, int end){
        List<Point> points = new ArrayList<>();
        Iterator<Integer> iterator = map.keySet().iterator();
        int i;

        while(iterator.hasNext() && (i=iterator.next()) <= end){
            points.add(map.get(i).getPoint1());
        }
        points.add(map.get(end).getPoint2());
        return points;
    }


    private static List<Point> getPoints(List<Line> lineSegments) {

        List<Point> points =  new ArrayList<>();
        Iterator itr = lineSegments.iterator();

        while(itr.hasNext()){
            Line segment = (Line)itr.next();
            points.add(new Point(segment.getPoint1()));
        }
        points.add(new Point(lineSegments.get(lineSegments.size()-1).getPoint2()));
        return points;
    }
}