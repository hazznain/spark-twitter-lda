<?php
  $username = "myuser"; 
  $password = "mypassword";   
  $host = "localhost";
  $database="Tweets";
    
  $mysqli = new mysqli($host, $username, $password, $database);
  $mysqli->set_charset('utf8');

  //Output any connection error 
  if ($mysqli->connect_error) {
    die('Error : ('. $mysqli->connect_errno .') '. $mysqli->connect_error);
  }

  $query = "SELECT tweets.partition AS date, count(*) AS amount, LDAResults.LDA AS info 
            FROM tweets LEFT JOIN LDAResults ON tweets.partition=LDAResults.peak_at GROUP BY date;";


  $myArray = array();
  if ($result = $mysqli->query($query)) {

    while($row = $result->fetch_array(MYSQL_ASSOC)) {
            $myArray[] = $row;
    }
    echo json_encode($myArray);
  }

  $result->close();
  $mysqli->close();
?>